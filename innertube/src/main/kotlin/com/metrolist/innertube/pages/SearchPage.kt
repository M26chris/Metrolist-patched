package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime
import com.metrolist.innertube.utils.ParserDebugger

data class SearchResult(
    val items: List<YTItem>,
    val continuation: String? = null,
)

object SearchPage {
    fun toYTItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
        val secondaryLine =
            renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.splitBySeparator()
                ?: run {
                    ParserDebugger.traceSkippedItem(
                        "SearchPage", 
                        "Aborted: secondaryLine extraction yielded null (Column 1 text runs missing or unparseable)", 
                        renderer
                    )
                    return null
                }
                
        return when {
            // CRITICAL: Check isEpisode BEFORE isSong — both can match isSong (watchEndpoint or
            // null navigationEndpoint), so episodes must be identified first.
            renderer.isEpisode -> {
                val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

                val podcastSectionIndex = secondaryLine.indexOfFirst { section ->
                    section.any { run ->
                        run.navigationEndpoint?.browseEndpoint
                            ?.browseEndpointContextSupportedConfigs
                            ?.browseEndpointContextMusicConfig
                            ?.pageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
                    }
                }

                val podcast = if (podcastSectionIndex >= 0) {
                    secondaryLine[podcastSectionIndex].firstOrNull()?.let { run ->
                        val bId = run.navigationEndpoint?.browseEndpoint?.browseId
                        if (bId == null) {
                            ParserDebugger.traceSkippedItem("SearchPage [Episode Branch]", "Skipped: podcast browsing token id evaluates to null", renderer)
                            return null
                        }
                        Album(
                            name = run.text,
                            id = bId,
                        )
                    }
                } else null

                val publishDateText = if (podcastSectionIndex > 0)
                    secondaryLine.getOrNull(podcastSectionIndex - 1)?.firstOrNull()?.text
                else null

                val itemId = 
                    renderer.playlistItemData?.videoId 
                        ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                        ?: renderer.navigationEndpoint?.watchPlaylistEndpoint?.videoId

                val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                if (itemId == null || itemTitle == null || thumbUrl == null) {
                    ParserDebugger.traceSkippedItem("SearchPage [Episode Branch]", "Skipped: Vital parameters missing -> id=$itemId, title=$itemTitle, thumb=$thumbUrl", renderer)
                    return null
                }

                EpisodeItem(
                    id = itemId,
                    title = itemTitle,
                    author = null,
                    podcast = podcast,
                    duration =
                        secondaryLine
                            .lastOrNull()
                            ?.firstOrNull()
                            ?.text
                            ?.parseTime(),
                    publishDateText = publishDateText,
                    thumbnail = thumbUrl,
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                    endpoint = renderer.overlay
                        ?.musicItemThumbnailOverlayRenderer
                        ?.content
                        ?.musicPlayButtonRenderer
                        ?.playNavigationEndpoint
                        ?.watchEndpoint
                        ?: renderer.navigationEndpoint?.watchEndpoint,
                    libraryAddToken = libraryTokens.addToken,
                    libraryRemoveToken = libraryTokens.removeToken,
                )
            }
            renderer.isSong -> {
                val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

                val itemId = renderer.playlistItemData?.videoId ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                val artistsList = secondaryLine.firstOrNull()?.oddElements()?.map {
                    Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                }
                val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                ParserDebugger.log(
                    """
                    SONG PARSE

                    title=$itemTitle

                    playlistItemId=${renderer.playlistItemData?.videoId}
                    watchId=${renderer.navigationEndpoint?.watchEndpoint?.videoId}
                    watchPlaylistId=${renderer.navigationEndpoint?.watchPlaylistEndpoint?.videoId}

                    artistsList=$artistsList

                    thumb=$thumbUrl
                    """.trimIndent()
                )

                if (itemId == null || itemTitle == null || artistsList == null || thumbUrl == null) {
                    ParserDebugger.traceSkippedItem("SearchPage [Song Branch]", "Skipped: Vital parameters missing -> id=$itemId, title=$itemTitle, artistsExist=${artistsList != null}, thumb=$thumbUrl", renderer)
                    return null
                }

                SongItem(
                    id = itemId,
                    title = itemTitle,
                    artists = artistsList,
                    album =
                        secondaryLine.getOrNull(1)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                            val aId = it.navigationEndpoint?.browseEndpoint?.browseId
                            if (aId == null) null else Album(name = it.text, id = aId)
                        },
                    duration =
                        secondaryLine
                            .lastOrNull()
                            ?.firstOrNull()
                            ?.text
                            ?.parseTime(),
                    musicVideoType = renderer.musicVideoType,
                    thumbnail = thumbUrl,
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                    libraryAddToken = libraryTokens.addToken,
                    libraryRemoveToken = libraryTokens.removeToken,
                    isEpisode = renderer.isEpisode
                )
            }
            renderer.isArtist -> {
                val itemId = renderer.navigationEndpoint?.browseEndpoint?.browseId
                val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                val shuffleEp = renderer.menu?.menuRenderer?.items?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                val radioEp = renderer.menu?.menuRenderer?.items?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint

                if (itemId == null || itemTitle == null || thumbUrl == null || shuffleEp == null || radioEp == null) {
                    ParserDebugger.traceSkippedItem("SearchPage [Artist Branch]", "Skipped: Missing params -> id=$itemId, title=$itemTitle, thumb=$thumbUrl, shuffle=${shuffleEp != null}, radio=${radioEp != null}", renderer)
                    return null
                }

                ArtistItem(
                    id = itemId,
                    title = itemTitle,
                    thumbnail = thumbUrl,
                    shuffleEndpoint = shuffleEp,
                    radioEndpoint = radioEp,
                )
            }
            renderer.isUserChannel -> {
                val itemId = renderer.navigationEndpoint?.browseEndpoint?.browseId
                val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                if (itemId == null || itemTitle == null || thumbUrl == null) {
                    ParserDebugger.traceSkippedItem("SearchPage [UserChannel Branch]", "Skipped: Missing params -> id=$itemId, title=$itemTitle, thumb=$thumbUrl", renderer)
                    return null
                }

                ArtistItem(
                    id = itemId,
                    title = itemTitle,
                    thumbnail = thumbUrl,
                    shuffleEndpoint = renderer.menu
                        ?.menuRenderer
                        ?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                        ?.menuNavigationItemRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu
                        ?.menuRenderer
                        ?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                        ?.menuNavigationItemRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint,
                    isProfile = true,
                )
            }
            renderer.isAlbum -> {
                val itemId = renderer.navigationEndpoint?.browseEndpoint?.browseId
                val playlistId = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.anyWatchEndpoint?.playlistId
                val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                val artistsList = secondaryLine.getOrNull(1)?.oddElements()?.map {
                    Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                }
                val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                if (itemId == null || playlistId == null || itemTitle == null || artistsList == null || thumbUrl == null) {
                    ParserDebugger.traceSkippedItem("SearchPage [Album Branch]", "Skipped: Missing params -> id=$itemId, playlistId=$playlistId, title=$itemTitle, artistsExist=${artistsList != null}, thumb=$thumbUrl", renderer)
                    return null
                }

                AlbumItem(
                    browseId = itemId,
                    playlistId = playlistId,
                    title = itemTitle,
                    artists = artistsList,
                    year =
                        secondaryLine
                            .getOrNull(2)
                            ?.firstOrNull()
                            ?.text
                            ?.toIntOrNull(),
                    thumbnail = thumbUrl,
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                )
            }
            renderer.isPlaylist -> {
                val itemId = renderer.navigationEndpoint?.browseEndpoint?.browseId?.removePrefix("VL")
                val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                val itemAuthor = secondaryLine.firstOrNull()?.firstOrNull()?.let {
                    Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)
                }
                val songCount = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.lastOrNull()?.text
                val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                val playEp = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint
                val shuffleEp = renderer.menu?.menuRenderer?.items?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                val radioEp = renderer.menu?.menuRenderer?.items?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint

                if (itemId == null || itemTitle == null || itemAuthor == null || songCount == null || thumbUrl == null || playEp == null || shuffleEp == null || radioEp == null) {
                    ParserDebugger.traceSkippedItem("SearchPage [Playlist Branch]", "Skipped: Missing elements -> id=$itemId, title=$itemTitle, authorExist=${itemAuthor != null}, count=$songCount, play=${playEp != null}", renderer)
                    return null
                }

                PlaylistItem(
                    id = itemId,
                    title = itemTitle,
                    author = itemAuthor,
                    songCountText = songCount,
                    thumbnail = thumbUrl,
                    playEndpoint = playEp,
                    shuffleEndpoint = shuffleEp,
                    radioEndpoint = radioEp,
                )
            }
            renderer.isPodcast -> {
                val itemId = renderer.navigationEndpoint?.browseEndpoint?.browseId
                val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                if (itemId == null || itemTitle == null || thumbUrl == null) {
                    ParserDebugger.traceSkippedItem("SearchPage [Podcast Branch]", "Skipped: Missing params -> id=$itemId, title=$itemTitle, thumb=$thumbUrl", renderer)
                    return null
                }

                PodcastItem(
                    id = itemId,
                    title = itemTitle,
                    author =
                        secondaryLine.firstOrNull()?.firstOrNull()?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        },
                    episodeCountText =
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.lastOrNull()
                            ?.text,
                    thumbnail = thumbUrl,
                    playEndpoint =
                        renderer.overlay
                            ?.musicItemThumbnailOverlayRenderer
                            ?.content
                            ?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint,
                    shuffleEndpoint =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint,
                )
            }
            else -> {
                // Pinpoints rows that don't trigger ANY of the inner tube boolean configuration type categories
                ParserDebugger.traceSkippedItem(
                    "SearchPage", 
                    "Aborted: Item fell completely through 'when' mapping conditions (No matched boolean configuration).", 
                    renderer
                )
                null
            }
        }
    }
}