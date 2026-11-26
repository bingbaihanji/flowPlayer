package com.bingbaihanji.flowplayer.core.data.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bingbaihanji.flowplayer.core.data.file.FileSortBy
import com.bingbaihanji.flowplayer.core.player.PlayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.SecureRandom

/**
 * 进程内唯一的设置 DataStore。
 *
 * 文件名与 M5 计划一致；委托属性保证同一进程内不会创建第二个实例，
 * [SettingsRepository] 的多个对象共享同一份数据与监听。
 */
private val Context.flowplayerSettingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "flowplayer_settings")

/**
 * 应用设置的唯一入口（DataStore Preferences）。
 *
 * 约定：
 * - 只读通过 [settings] 的 Flow 暴露，UI 层拿到的是 [AppSettings] 快照；
 * - 写入只能走本类的 setter，每次 setter 一个 `edit` 事务；
 * - 枚举以 `name` 字符串存储，读取时容错解析，非法值回落默认值；
 * - 读取 IOException（磁盘损坏/无法访问）时返回默认快照，不影响 App 使用；
 * - 所有挂起方法内部由 DataStore 调度到 IO，调用方不要用 runBlocking 在主线程等待。
 */
class SettingsRepository private constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val secureRandom = SecureRandom()

    /**
     * 设置快照流。每个键缺失或无法解析时使用 [AppSettings] 中的默认值。
     */
    val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            // 只把 IO 故障当成“还没有设置”处理，其余异常继续向上抛出。
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences -> preferences.toAppSettings() }

    suspend fun setPlayMode(mode: PlayMode) {
        dataStore.edit { preferences -> preferences[KEY_PLAY_MODE] = mode.name }
    }

    suspend fun setSpeedMode(mode: SpeedMode) {
        dataStore.edit { preferences -> preferences[KEY_SPEED_MODE] = mode.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setContinueLastPosition(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_CONTINUE_LAST_POSITION] = enabled }
    }

    suspend fun setShowHiddenFiles(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_SHOW_HIDDEN_FILES] = enabled }
    }

    /**
     * 排序字段与方向在同一次事务里写入，避免监听方看到“新字段 + 旧方向”的中间态。
     */
    suspend fun setSort(by: FileSortBy, ascending: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_SORT_BY] = by.name
            preferences[KEY_SORT_ASCENDING] = ascending
        }
    }

    suspend fun setLibraryGridView(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_LIBRARY_GRID_VIEW] = enabled }
    }

    /**
     * 确保传输 token 存在：已有非空值直接复用；否则生成 URL-safe 随机 token 并持久化。
     *
     * 并发调用时以先落盘的值为准，后到者返回同一 token。
     * DataStore 读写异常会向上抛出，由 TransferService 回退到内存随机 token。
     */
    suspend fun ensureTransferToken(): String {
        val persisted = dataStore.data.first()[KEY_TRANSFER_TOKEN]
        if (!persisted.isNullOrBlank()) return persisted

        val generated = generateToken()
        val updated = dataStore.edit { preferences ->
            if (preferences[KEY_TRANSFER_TOKEN].isNullOrBlank()) {
                preferences[KEY_TRANSFER_TOKEN] = generated
            }
        }
        return updated[KEY_TRANSFER_TOKEN]?.takeIf { it.isNotBlank() } ?: generated
    }

    /** 生成可直接放进 URL query 的 32 字符 token（无填充、无 `+ /`）。 */
    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    /** 把 Preferences 映射为快照；每个字段独立容错，互不影响。 */
    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        playMode = readEnum(this[KEY_PLAY_MODE], PlayMode.SEQUENTIAL),
        speedMode = readEnum(this[KEY_SPEED_MODE], SpeedMode.NORMAL),
        themeMode = readEnum(this[KEY_THEME_MODE], ThemeMode.FOLLOW_SYSTEM),
        continueLastPosition = this[KEY_CONTINUE_LAST_POSITION] ?: true,
        showHiddenFiles = this[KEY_SHOW_HIDDEN_FILES] ?: false,
        sortBy = readEnum(this[KEY_SORT_BY], FileSortBy.TIME),
        sortAscending = this[KEY_SORT_ASCENDING] ?: false,
        libraryGridView = this[KEY_LIBRARY_GRID_VIEW] ?: false,
        transferToken = this[KEY_TRANSFER_TOKEN].orEmpty(),
    )

    companion object {
        private val KEY_PLAY_MODE = stringPreferencesKey("play_mode")
        private val KEY_SPEED_MODE = stringPreferencesKey("speed_mode")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_CONTINUE_LAST_POSITION = booleanPreferencesKey("continue_last_position")
        private val KEY_SHOW_HIDDEN_FILES = booleanPreferencesKey("show_hidden_files")
        private val KEY_SORT_BY = stringPreferencesKey("sort_by")
        private val KEY_SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
        private val KEY_LIBRARY_GRID_VIEW = booleanPreferencesKey("library_grid_view")
        private val KEY_TRANSFER_TOKEN = stringPreferencesKey("transfer_token")

        private const val TOKEN_BYTES = 24

        /** 获取仓库实例；底层 DataStore 由文件级委托属性保证进程内唯一。 */
        fun from(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.flowplayerSettingsDataStore)
    }
}

/**
 * 读取枚举字符串；值缺失或不在枚举范围内时返回默认值（例如旧版本残留/手工改坏的数据）。
 */
private inline fun <reified T : Enum<T>> readEnum(raw: String?, default: T): T =
    raw?.let { value ->
        runCatching { enumValueOf<T>(value) }.getOrNull()
    } ?: default
