package com.fabrice.spaceskysea

/** Comparaison de versions "v1.2.3" (logique pure, testable sans Android). */
object VersionUtils {

    /** "v1.2.3-debug" → "1.2.3" */
    fun normalize(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-')

    /** true si [remote] est strictement plus récente que [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = normalize(remote).split('.').map { it.toIntOrNull() ?: 0 }
        val c = normalize(current).split('.').map { it.toIntOrNull() ?: 0 }
        if (r.all { it == 0 }) return false // version distante illisible
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
