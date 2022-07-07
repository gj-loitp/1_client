package com.looker.droidify.content

import android.content.Context
import android.content.SharedPreferences
import com.looker.droidify.database.Database
import com.looker.droidify.entity.ProductPreference
import com.looker.droidify.utility.extension.json.Json
import com.looker.droidify.utility.extension.json.parseDictionary
import com.looker.droidify.utility.extension.json.writeDictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object ProductPreferences {
	private val defaultProductPreference = ProductPreference(false, 0L)
	private lateinit var preferences: SharedPreferences
	private val mutableSubject = MutableSharedFlow<Pair<String, Long?>>()
	private val subject = mutableSubject.asSharedFlow()

	fun init(context: Context) {
		preferences = context.getSharedPreferences("product_preferences", Context.MODE_PRIVATE)
		Database.LockAdapter.putAll(preferences.all.keys
			.mapNotNull { packageName ->
				this[packageName].databaseVersionCode?.let { Pair(packageName, it) }
			})
		CoroutineScope(Dispatchers.Default).launch {
			subject.collect { (packageName, versionCode) ->
				if (versionCode != null) Database.LockAdapter.put(Pair(packageName, versionCode))
				else Database.LockAdapter.delete(packageName)
			}
		}
	}

	private val ProductPreference.databaseVersionCode: Long?
		get() = when {
			ignoreUpdates -> 0L
			ignoreVersionCode > 0L -> ignoreVersionCode
			else -> null
		}

	operator fun get(packageName: String): ProductPreference {
		return if (preferences.contains(packageName)) {
			try {
				Json.factory.createParser(preferences.getString(packageName, "{}"))
					.use { it.parseDictionary(ProductPreference.Companion::deserialize) }
			} catch (e: Exception) {
				e.printStackTrace()
				defaultProductPreference
			}
		} else {
			defaultProductPreference
		}
	}

	operator fun set(packageName: String, productPreference: ProductPreference) {
		val oldProductPreference = this[packageName]
		preferences.edit().putString(packageName, ByteArrayOutputStream()
			.apply {
				Json.factory.createGenerator(this)
					.use { it.writeDictionary(productPreference::serialize) }
			}
			.toByteArray().toString(Charset.defaultCharset())).apply()
		if (oldProductPreference.ignoreUpdates != productPreference.ignoreUpdates ||
			oldProductPreference.ignoreVersionCode != productPreference.ignoreVersionCode
		) {
			CoroutineScope(Dispatchers.Default).launch {
				mutableSubject.emit(Pair(packageName, productPreference.databaseVersionCode))
			}
		}
	}
}