package com.bingbaihanji.flowplayer.core.data.file

import java.text.Collator
import java.util.*

/** 文件排序字段。 */
enum class FileSortBy {
    NAME,
    TIME,
    SIZE,
    TYPE,
}

/**
 * 文件/相册排序配置。目录永远排在普通文件之前，升序/降序只影响同类条目。
 *
 * 默认按修改时间倒序，与参考应用文件列表的默认顺序一致。
 */
data class FileSortSpec(
    val by: FileSortBy = FileSortBy.TIME,
    val ascending: Boolean = false,
) {

    fun fileComparator(): Comparator<MediaFile> {
        // Collator 非线程安全，每次比较器创建独立实例；中文按拼音/本地规则排序。
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        return Comparator { left, right ->
            if (left.isDirectory != right.isDirectory) {
                return@Comparator if (left.isDirectory) -1 else 1
            }
            val primary = when (by) {
                FileSortBy.NAME -> collator.compare(left.title, right.title)
                FileSortBy.TIME -> left.lastModified.compareTo(right.lastModified)
                FileSortBy.SIZE -> left.size.compareTo(right.size)
                FileSortBy.TYPE -> left.kind.ordinal.compareTo(right.kind.ordinal)
            }
            val directed = if (ascending) primary else -primary
            if (directed != 0) {
                directed
            } else {
                val byName = collator.compare(left.title, right.title)
                if (ascending) byName else -byName
            }
        }
    }

    fun bucketComparator(): Comparator<MediaBucket> {
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        return Comparator { left, right ->
            val primary = when (by) {
                FileSortBy.NAME -> collator.compare(left.name, right.name)
                FileSortBy.TIME -> left.latestModified.compareTo(right.latestModified)
                FileSortBy.SIZE -> left.totalSize.compareTo(right.totalSize)
                // 相册没有“类型”维度，按数量排序。
                FileSortBy.TYPE -> left.count.compareTo(right.count)
            }
            val directed = if (ascending) primary else -primary
            if (directed != 0) {
                directed
            } else {
                val byName = collator.compare(left.name, right.name)
                if (ascending) byName else -byName
            }
        }
    }
}
