package com.aichatvn.agent.utils

import java.text.Normalizer

object StringSimilarityUtil {
    private val SPACE_REGEX = Regex("\\s+")

    fun normalizeVietnamese(text: String): String {
        val temp = Normalizer.normalize(text, Normalizer.Form.NFD)
        val regex = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        return regex.replace(temp, "")
            .replace("đ", "d")
            .replace("Đ", "D")
            .lowercase()
            .trim()
            .replace(SPACE_REGEX, " ")
    }

    fun calculateLocalSimilarity(clean1: String, clean2: String): Double {
        if (clean1.isEmpty() || clean2.isEmpty()) return 0.0
        if (clean1 == clean2) return 1.0

        // Khai báo ban đầu cho độ lệch và độ dài tối đa
        val lenDiff = Math.abs(clean1.length - clean2.length)
        val maxLen = maxOf(clean1.length, clean2.length)
        if (maxLen > 10 && lenDiff.toDouble() / maxLen > 0.7) {
            return 0.0
        }

        val tokens1 = clean1.split(SPACE_REGEX).toSet()
        val tokens2 = clean2.split(SPACE_REGEX).toSet()
        val intersectionSize = tokens1.intersect(tokens2).size.toDouble()

        if (tokens1.size > 1 && tokens2.size > 1 && intersectionSize == 0.0) {
            return 0.0
        }

        val isWordMatch = when {
            clean1.contains(clean2) -> {
                val index = clean1.indexOf(clean2)
                val charBefore = if (index > 0) clean1[index - 1] else ' '
                val charAfter = if (index + clean2.length < clean1.length) clean1[index + clean2.length] else ' '
                charBefore.isWhitespace() && charAfter.isWhitespace()
            }
            clean2.contains(clean1) -> {
                val index = clean2.indexOf(clean1)
                val charBefore = if (index > 0) clean2[index - 1] else ' '
                val charAfter = if (index + clean1.length < clean2.length) clean2[index + clean1.length] else ' '
                charBefore.isWhitespace() && charAfter.isWhitespace()
            }
            else -> false
        }

        // Tái sử dụng trực tiếp các biến lenDiff và maxLen đã khai báo ở đầu hàm
        if (isWordMatch && (lenDiff.toDouble() / maxLen < 0.25)) {
            return 0.95
        }

        val union = tokens1.union(tokens2).size.toDouble()
        val jaccard = if (union > 0) intersectionSize / union else 0.0

        val lenDistance = levenshteinDistance(clean1, clean2)
        val levSim = 1.0 - (lenDistance.toDouble() / maxLen)

        return (jaccard * 0.4) + (levSim * 0.6)
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}