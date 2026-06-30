package com.aichatvn.agent.utils

object DateTimeParser {

    fun parseVietnameseTime(message: String): String? {
        val lower = message.lowercase().trim()
        
        var extractedHour: Int? = null
        var extractedMinute: Int = 0
        
        val digitalRegex = Regex("\\b(\\d{1,2}):(\\d{2})\\s*(sáng|chiều|tối|đêm)?\\b")
        val digitalMatch = digitalRegex.find(lower)
        
        if (digitalMatch != null) {
            var hour = digitalMatch.groupValues[1].toIntOrNull() ?: 0
            extractedMinute = digitalMatch.groupValues[2].toIntOrNull() ?: 0
            val period = digitalMatch.groupValues[3]
            
            if ((period == "chiều" || period == "tối") && hour < 12) {
                hour += 12
            } else if (period == "đêm" && hour == 12) {
                hour = 0
            }
            extractedHour = hour
        } else {
            val hourRegex = Regex("\\b(\\d+)\\s*(giờ|g|h)\\s*(sáng|chiều|tối|đêm)?\\b")
            val hourMatch = hourRegex.find(lower)
            if (hourMatch != null) {
                var hour = hourMatch.groupValues[1].toIntOrNull() ?: 0
                val period = hourMatch.groupValues[3]
                if ((period == "chiều" || period == "tối") && hour < 12) {
                    hour += 12
                } else if (period == "đêm" && hour == 12) {
                    hour = 0
                }
                extractedHour = hour
            }
        }

        val days = mutableListOf<String>()
        
        val mondayRegex = Regex("\\b(thứ hai|thứ 2)\\b")
        val tuesdayRegex = Regex("\\b(thứ ba|thứ 3)\\b")
        val wednesdayRegex = Regex("\\b(thứ tư|thứ 4)\\b")
        val thursdayRegex = Regex("\\b(thứ năm|thứ 5)\\b")
        val fridayRegex = Regex("\\b(thứ sáu|thứ 6)\\b")
        val saturdayRegex = Regex("\\b(thứ bảy|thứ 7)\\b")
        val sundayRegex = Regex("\\b(chủ nhật|cn)\\b")

        if (mondayRegex.containsMatchIn(lower)) days.add("1")
        if (tuesdayRegex.containsMatchIn(lower)) days.add("2")
        if (wednesdayRegex.containsMatchIn(lower)) days.add("3")
        if (thursdayRegex.containsMatchIn(lower)) days.add("4")
        if (fridayRegex.containsMatchIn(lower)) days.add("5")
        if (saturdayRegex.containsMatchIn(lower)) days.add("6")
        if (sundayRegex.containsMatchIn(lower)) days.add("0")

        if (days.isNotEmpty()) {
            val hour = extractedHour ?: 0
            val dayOfWeek = days.joinToString(",")
            return "$extractedMinute $hour * * $dayOfWeek"
        }

        val dailyRegex = Regex("\\b(mỗi ngày|hằng ngày|hàng ngày)\\b")
        if (dailyRegex.containsMatchIn(lower)) {
            val hour = extractedHour ?: 0
            return "$extractedMinute $hour * * *"
        }
        
        val weeklyRegex = Regex("\\b(hằng tuần|hàng tuần)\\b")
        if (weeklyRegex.containsMatchIn(lower)) {
            val hour = extractedHour ?: 0
            return "$extractedMinute $hour * * 0"
        }
        
        val tomorrowRegex = Regex("\\b(ngày mai|mai)\\b")
        if (tomorrowRegex.containsMatchIn(lower)) {
            val hour = extractedHour ?: 8
            return "$extractedMinute $hour * * *"
        }

        if (extractedHour != null) {
            return "$extractedMinute $extractedHour * * *"
        }

        return null
    }

    fun parseVietnameseInterval(message: String): Int? {
        val lower = message.lowercase()
        val intervalRegex = Regex("mỗi\\s*(\\d+)\\s*phút")
        val match = intervalRegex.find(lower)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()
        }
        return null
    }
}