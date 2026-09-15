package com.jake.duolauncher

import androidx.compose.foundation.pager.PagerState
import androidx.compose.animation.core.spring

/** Home/drop indices stay zero based; Discover is logical page -1. */
internal class LauncherPager(val state: PagerState, private val firstHome: Int) {
    val currentPage get() = state.currentPage - firstHome
    val settledPage get() = state.settledPage - firstHome
    fun requestScrollToPage(page: Int) = state.requestScrollToPage(page + firstHome)
    suspend fun scrollToPage(page: Int) = state.scrollToPage(page + firstHome)
    suspend fun animateScrollToPage(page: Int) = state.animateScrollToPage(
        page + firstHome,
        animationSpec = spring(dampingRatio = 0.92f, stiffness = 650f),
    )
}
