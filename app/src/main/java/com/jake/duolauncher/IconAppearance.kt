package com.jake.duolauncher

import android.graphics.Path

enum class IconShape(val label: String) {
    ROUNDED_SQUARE("Rounded square"),
    CIRCLE("Circle"),
    SQUIRCLE("Squircle"),
    SQUARE("Square");

    fun path(size: Float): Path = Path().apply {
        when (this@IconShape) {
            ROUNDED_SQUARE -> addRoundRect(0f, 0f, size, size, size * .235f, size * .235f, Path.Direction.CW)
            CIRCLE -> addCircle(size / 2, size / 2, size / 2, Path.Direction.CW)
            SQUIRCLE -> addRoundRect(0f, 0f, size, size, size * .37f, size * .37f, Path.Direction.CW)
            SQUARE -> addRect(0f, 0f, size, size, Path.Direction.CW)
        }
    }

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: ROUNDED_SQUARE
    }
}
