package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicCardShelfRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.clean
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitArtistsByConjunction
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime
import com.metrolist.innertube.utils.ParserDebugger


data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
)

data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
) {
    fun filterExplicit(enabled: Boolean) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterExplicit().ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterVideoSongs(disableVideos: Boolean) =
        if (disableVideos) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterVideoSongs(true).ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    fun filterYoutubeShorts(enabled: Boolean = false) =
        if (enabled) {
            SearchSummaryPage(
                summaries.mapNotNull { s ->
                    SearchSummary(
                        title = s.title,
                        items =
                            s.items.filterYoutubeShorts(true).ifEmpty {
                                return@mapNotNull null
                            },
                    )
                },
            )
        } else {
            this
        }

    companion object {
        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            return when {
                renderer.onTap.watchEndpoint != null -> {
                    val mId = 
                        renderer.onTap.watchEndpoint.videoId
                            ?: renderer.onTap.watchPlaylistEndpoint?.videoId

                    val mTitle = renderer.title.runs?.firstOrNull()?.text
                    val artistsList = subtitle?.getOrNull(1)?.oddElements()?.map { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                    val thumbUrl = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl()

                    if (mId == null || mTitle == null || artistsList == null || thumbUrl == null) {
                        ParserDebugger.traceSkippedItem("CardShelf [Watch Branch]", "Missing data params", renderer)
                        return null
                    }
                    SongItem(
                        id = mId,
                        title = mTitle,
                        artists = artistsList,
                        album =
                            subtitle.getOrNull(2)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                                val bId = it.navigationEndpoint?.browseEndpoint?.browseId
                                if (bId == null) null else Album(name = it.text, id = bId)
                            },
                        duration =
                            subtitle
                                .lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        musicVideoType = renderer.onTap.musicVideoType,
                        thumbnail = thumbUrl,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    val mId = renderer.onTap.browseEndpoint.browseId
                    val mTitle = renderer.title.runs?.firstOrNull()?.text
                    val thumbUrl = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl()
                    val shuffleEp = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }?.buttonRenderer?.command?.watchPlaylistEndpoint
                    val radioEp = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "MIX" }?.buttonRenderer?.command?.watchPlaylistEndpoint

                    if (mId == null || mTitle == null || thumbUrl == null || shuffleEp == null || radioEp == null) {
                        ParserDebugger.traceSkippedItem("CardShelf [Artist Branch]", "Missing data params", renderer)
                        return null
                    }
                    ArtistItem(
                        id = mId,
                        title = mTitle,
                        thumbnail = thumbUrl,
                        shuffleEndpoint = shuffleEp,
                        radioEndpoint = radioEp,
                    )
                }

                renderer.onTap.browseEndpoint?.isAlbumEndpoint == true -> {
                    val mId = renderer.onTap.browseEndpoint.browseId
                    val pId = renderer.buttons.firstOrNull()?.buttonRenderer?.command?.anyWatchEndpoint?.playlistId
                    val mTitle = renderer.title.runs?.firstOrNull()?.text
                    val artistsList = subtitle?.getOrNull(1)?.oddElements()?.map { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                    val thumbUrl = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl()

                    if (mId == null || pId == null || mTitle == null || artistsList == null || thumbUrl == null) {
                        ParserDebugger.traceSkippedItem("CardShelf [Album Branch]", "Missing data params", renderer)
                        return null
                    }
                    AlbumItem(
                        browseId = mId,
                        playlistId = pId,
                        title = mTitle,
                        artists = artistsList,
                        year = null,
                        thumbnail = thumbUrl,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    val mId = renderer.onTap.browseEndpoint.browseId.removePrefix("VL")
                    val mTitle = renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs?.joinToString(separator = "") { it.text }
                    val mAuthorName = renderer.subtitle.runs?.joinToString { it.text }
                    val thumbUrl = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl()
                    val playEp = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }?.buttonRenderer?.command?.watchPlaylistEndpoint
                    val shuffleEp = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }?.buttonRenderer?.command?.watchPlaylistEndpoint

                    if (mId == null || mTitle == null || mAuthorName == null || thumbUrl == null || playEp == null || shuffleEp == null) {
                        ParserDebugger.traceSkippedItem("CardShelf [Playlist Branch]", "Missing data params", renderer)
                        return null
                    }
                    PlaylistItem(
                        id = mId,
                        title = mTitle,
                        author = Artist(id = null, name = mAuthorName),
                        songCountText = null,
                        thumbnail = thumbUrl,
                        playEndpoint = playEp,
                        shuffleEndpoint = shuffleEp,
                        radioEndpoint = null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPodcastEndpoint == true -> {
                    val mId = renderer.onTap.browseEndpoint.browseId
                    val mTitle = renderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs?.joinToString(separator = "") { it.text }
                    val mAuthorName = renderer.subtitle.runs?.joinToString { it.text }
                    val thumbUrl = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl()

                    if (mId == null || mTitle == null || mAuthorName == null || thumbUrl == null) {
                        ParserDebugger.traceSkippedItem("CardShelf [Podcast Branch]", "Missing data params", renderer)
                        return null
                    }
                    PodcastItem(
                        id = mId,
                        title = mTitle,
                        author = Artist(id = null, name = mAuthorName),
                        episodeCountText = null,
                        thumbnail = thumbUrl,
                        playEndpoint = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }?.buttonRenderer?.command?.watchPlaylistEndpoint,
                        shuffleEndpoint = renderer.buttons.find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }?.buttonRenderer?.command?.watchPlaylistEndpoint,
                    )
                }
                else -> {
                    ParserDebugger.traceSkippedItem("CardShelf", "Card layout fell through entirely", renderer)
                    null
                }
            }
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
            renderer.debugClassification()
            renderer.debugEndpoints()
            
            val secondaryLine =
                renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    ?: run {
                        ParserDebugger.traceSkippedItem("SearchSummaryPage", "Aborted: secondaryLine extraction evaluated to null", renderer)
                        return null
                    }
                    
            return when {
                // CRITICAL: Check isEpisode BEFORE isSong because both have videoId and no browseEndpoint
                // Episodes are identified by firstSubtitle == "Episode" in unfiltered search
                renderer.isEpisode -> {
                    val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

                    val firstSubtitle = secondaryLine.getOrNull(0)?.firstOrNull()?.text
                    val isUnfilteredSearch = firstSubtitle == "Episode"
                    val dateIndex = if (isUnfilteredSearch) 1 else 0
                    val podcastIndex = if (isUnfilteredSearch) 2 else 1

                    val itemId = renderer.playlistItemData?.videoId ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                    val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                    val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                    if (itemId == null || itemTitle == null || thumbUrl == null) {
                        ParserDebugger.traceSkippedItem("SearchSummaryPage [Episode Branch]", "Skipped: Missing core parameters -> id=$itemId, title=$itemTitle", renderer)
                        return null
                    }

                    EpisodeItem(
                        id = itemId,
                        title = itemTitle,
                        author = null,
                        podcast =
                            secondaryLine.getOrNull(podcastIndex)?.firstOrNull()?.takeIf {
                                it.navigationEndpoint?.browseEndpoint != null
                            }?.let {
                                val bId = it.navigationEndpoint?.browseEndpoint?.browseId
                                if (bId == null) null else Album(name = it.text, id = bId)
                            },
                        duration =
                            secondaryLine
                                .lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        publishDateText =
                            secondaryLine
                                .getOrNull(dateIndex)
                                ?.firstOrNull()
                                ?.text,
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
                            ?.watchEndpoint,
                        libraryAddToken = libraryTokens.addToken,
                        libraryRemoveToken = libraryTokens.removeToken,
                    )
                }

                renderer.isSong -> {
                    val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)
                    val thirdLine =
                        renderer.flexColumns
                            .getOrNull(2)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.splitBySeparator()
                            ?: emptyList()
                    val listRun = (secondaryLine + thirdLine).clean()

                    val artistRuns = listRun.getOrNull(0)?.splitArtistsByConjunction()
                        ?.filter { it.text.isNotBlank() && it.text != "&" && it.text != "," }

                    val itemId = renderer.playlistItemData?.videoId ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                    val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                    val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                    if (itemId == null || itemTitle == null || artistRuns == null || thumbUrl == null) {
                        ParserDebugger.traceSkippedItem("SearchSummaryPage [Song Branch]", "Skipped: Missing params -> id=$itemId, title=$itemTitle, artistsExist=${artistRuns != null}, thumb=$thumbUrl", renderer)
                        return null
                    }

                    SongItem(
                        id = itemId,
                        title = itemTitle,
                        artists = artistRuns.map { run ->
                            Artist(
                                name = run.text.trim(),
                                id = run.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        },
                        album = listRun.getOrNull(1)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                            val bId = it.navigationEndpoint?.browseEndpoint?.browseId
                            if (bId == null) null else Album(name = it.text, id = bId)
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
                        ParserDebugger.traceSkippedItem("SearchSummaryPage [Artist Branch]", "Skipped: Missing params -> id=$itemId, title=$itemTitle, thumb=$thumbUrl", renderer)
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
                        ParserDebugger.traceSkippedItem("SearchSummaryPage [UserChannel Branch]", "Skipped: Missing params -> id=$itemId, title=$itemTitle, thumb=$thumbUrl", renderer)
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
                    val playlistId = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint?.playlistId
                    val itemTitle = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                    val artistsList = secondaryLine.getOrNull(1)?.oddElements()?.map { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                    val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()

                    if (itemId == null || playlistId == null || itemTitle == null || artistsList == null || thumbUrl == null) {
                        ParserDebugger.traceSkippedItem("SearchSummaryPage [Album Branch]", "Skipped: Missing params -> id=$itemId, playlistId=$playlistId, title=$itemTitle, artistsExist=${artistsList != null}", renderer)
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
                    val itemAuthor = secondaryLine.getOrNull(1)?.firstOrNull()?.let { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                    val songCount = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.lastOrNull()?.text
                    val thumbUrl = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    val playEp = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint
                    val shuffleEp = renderer.menu?.menuRenderer?.items?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                    val radioEp = renderer.menu?.menuRenderer?.items?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint

                    if (itemId == null || itemTitle == null || itemAuthor == null || songCount == null || thumbUrl == null || playEp == null || shuffleEp == null || radioEp == null) {
                        ParserDebugger.traceSkippedItem("SearchSummaryPage [Playlist Branch]", "Skipped: Missing components -> id=$itemId, title=$itemTitle, authorExist=${itemAuthor != null}, count=$songCount", renderer)
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
                        ParserDebugger.traceSkippedItem("SearchSummaryPage [Podcast Branch]", "Skipped: Missing params -> id=$itemId, title=$itemTitle", renderer)
                        return null
                    }

                    PodcastItem(
                        id = itemId,
                        title = itemTitle,
                        author =
                            secondaryLine.getOrNull(0)?.firstOrNull()?.let {
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
                    ParserDebugger.traceSkippedItem(
                        "SearchSummaryPage", 
                        "Aborted: Row fell entirely through layout evaluation categories (isSong, isArtist, etc. all false).", 
                        renderer
                    )
                    null
                }
            }
        }
    }
}