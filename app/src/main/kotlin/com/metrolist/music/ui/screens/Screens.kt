/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.metrolist.music.R

@Immutable
sealed class Screens(
    @StringRes val titleId: Int,
    @DrawableRes val iconIdInactive: Int,
    @DrawableRes val iconIdActive: Int,
    val route: String,
) {
    object Home : Screens(
        titleId = R.string.home,
        iconIdInactive = R.drawable.home_outlined,
        iconIdActive = R.drawable.home_filled,
        route = "home"
    )

    object Search : Screens(
        titleId = R.string.search,
        iconIdInactive = R.drawable.search,
        iconIdActive = R.drawable.search,
        route = "search_input"
    )

    object Library : Screens(
        titleId = R.string.filter_library,
        iconIdInactive = R.drawable.library_music_outlined,
        iconIdActive = R.drawable.library_music_filled,
        route = "library"
    )

    // Aggiungi qui l'oggetto VoiceSearch
    object VoiceSearch : Screens(
        titleId = R.string.voice_search, // Assicurati di aver definito questa stringa in strings.xml
        iconIdInactive = R.drawable.ic_mic, // Assicurati di aver aggiunto questa drawable
        iconIdActive = R.drawable.ic_mic,   // Assicurati di aver aggiunto questa drawable
        route = "voice_search"
    )

    companion object {
        // Modificato: VoiceSearch ora fa parte di MainScreens, posizionata dove desideri che appaia.
        // Ad esempio, se vuoi che sia l'ultima icona della navigazione principale:
        val MainScreens = listOf(Home, VoiceSearch, Search, Library )
        // Se vuoi che sia la prima dopo Home e prima di Search:
        // val MainScreens = listOf(Home, VoiceSearch, Search, Library)
        // Per il tuo caso "insieme agli altri pulsanti in basso", la metterei in fondo come ultima.
    }
}
