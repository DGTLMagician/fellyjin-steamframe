package org.jellyfin.steamframe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.steamframe.BuildInfo
import java.net.InetAddress
import java.util.UUID

data class CurrentUser(val id: UUID, val name: String)

class JellyfinRepository(private val configStore: ConfigStore) {
	private val jellyfin = createJellyfin {
		clientInfo = ClientInfo(name = BuildInfo.CLIENT_NAME, version = BuildInfo.VERSION)
		deviceInfo = DeviceInfo(id = configStore.current.deviceId, name = deviceName())
	}

	val api: ApiClient = jellyfin.createApi()

	private val _user = MutableStateFlow<CurrentUser?>(null)
	val user: StateFlow<CurrentUser?> = _user.asStateFlow()

	val serverUrl: String? get() = api.baseUrl

	/** Restore the last session from the config, returns true when a signed-in session is available. */
	fun restoreSession(): Boolean {
		val server = configStore.current.server ?: return false
		api.update(baseUrl = server.url, accessToken = server.accessToken)
		val userId = server.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
		if (server.accessToken == null || userId == null) return false
		_user.value = CurrentUser(userId, server.userName.orEmpty())
		return true
	}

	// Server selection

	fun discoverLocalServers(): Flow<ServerDiscoveryInfo> = jellyfin.discovery.discoverLocalServers()

	/** Probe the address candidates for the user input and return the best one, if any is usable. */
	suspend fun findServer(input: String): RecommendedServerInfo? {
		val candidates = jellyfin.discovery.getRecommendedServers(input, RecommendedServerInfoScore.GOOD)
		return candidates.firstOrNull { it.score == RecommendedServerInfoScore.GREAT }
			?: candidates.firstOrNull { it.score == RecommendedServerInfoScore.GOOD }
	}

	fun selectServer(url: String, name: String?) {
		api.update(baseUrl = url, accessToken = null)
		_user.value = null
		configStore.update { it.copy(server = ServerConfig(url = url, name = name)) }
	}

	// Authentication

	suspend fun publicUsers(): List<UserDto> = api.userApi.getPublicUsers().content

	suspend fun login(username: String, password: String) {
		val result by api.authenticationApi.authenticateUserByName(username, password)
		onAuthenticated(result)
	}

	suspend fun initiateQuickConnect(): QuickConnectResult = api.authenticationApi.initiateQuickConnect().content

	suspend fun quickConnectState(secret: String): QuickConnectResult =
		api.authenticationApi.getQuickConnectState(secret).content

	suspend fun completeQuickConnect(secret: String) {
		val result by api.authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret))
		onAuthenticated(result)
	}

	private fun onAuthenticated(result: AuthenticationResult) {
		val token = requireNotNull(result.accessToken) { "Server did not return an access token" }
		val user = requireNotNull(result.user) { "Server did not return a user" }
		api.update(accessToken = token)
		_user.value = CurrentUser(user.id, user.name.orEmpty())
		configStore.update { config ->
			config.copy(
				server = ServerConfig(
					url = requireNotNull(api.baseUrl),
					name = config.server?.name,
					userId = user.id.toString(),
					userName = user.name,
					accessToken = token,
				)
			)
		}
	}

	suspend fun logout() {
		runCatching { api.sessionApi.reportSessionEnded() }
		api.update(accessToken = null)
		_user.value = null
		configStore.update { config -> config.copy(server = config.server?.copy(accessToken = null, userId = null)) }
	}

	// Content

	private val defaultFields = listOf(
		ItemFields.OVERVIEW,
		ItemFields.GENRES,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.CHILD_COUNT,
	)

	suspend fun userViews(): List<BaseItemDto> = api.userViewApi.getUserViews().content.items

	suspend fun resumeItems(): List<BaseItemDto> = api.libraryApi.getResumeItems(
		limit = 20,
		fields = defaultFields,
		includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO),
	).content.items

	suspend fun nextUp(): List<BaseItemDto> = api.showApi.getNextUp(
		limit = 20,
		fields = defaultFields,
		enableResumable = false,
	).content.items

	suspend fun latest(parentId: UUID): List<BaseItemDto> = api.libraryApi.getLatestMedia(
		parentId = parentId,
		fields = defaultFields,
		limit = 20,
	).content

	suspend fun libraryItems(parentId: UUID, startIndex: Int, limit: Int): Pair<List<BaseItemDto>, Int> {
		val result by api.libraryApi.getItems(
			parentId = parentId,
			startIndex = startIndex,
			limit = limit,
			recursive = true,
			includeItemTypes = listOf(
				BaseItemKind.MOVIE,
				BaseItemKind.SERIES,
				BaseItemKind.VIDEO,
				BaseItemKind.MUSIC_VIDEO,
				BaseItemKind.BOX_SET,
			),
			sortBy = listOf(ItemSortBy.SORT_NAME),
			sortOrder = listOf(SortOrder.ASCENDING),
			fields = defaultFields,
		)
		return result.items to result.totalRecordCount
	}

	suspend fun item(id: UUID): BaseItemDto = api.libraryApi.getItem(id).content

	suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
		api.showApi.getSeasons(seriesId, fields = defaultFields).content.items

	suspend fun episodes(seriesId: UUID, seasonId: UUID): List<BaseItemDto> =
		api.showApi.getEpisodes(seriesId, seasonId = seasonId, fields = defaultFields).content.items

	suspend fun nextEpisode(episode: BaseItemDto): BaseItemDto? {
		val seriesId = episode.seriesId ?: return null
		val episodes = api.showApi.getEpisodes(
			seriesId = seriesId,
			startItemId = episode.id,
			limit = 2,
			fields = defaultFields,
		).content.items
		return episodes.firstOrNull { it.id != episode.id }
	}

	suspend fun markPlayed(item: BaseItemDto) {
		api.userDataApi.markPlayedItem(item.id)
	}

	// Images

	fun imageUrl(itemId: UUID, type: ImageType, tag: String?, maxWidth: Int): String? {
		if (tag == null) return null
		return api.imageApi.getItemImageUrl(itemId = itemId, imageType = type, tag = tag, maxWidth = maxWidth, quality = 90)
	}

	/** Portrait poster, falls back to the series poster for episodes. */
	fun posterUrl(item: BaseItemDto, maxWidth: Int = 400): String? =
		imageUrl(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), maxWidth)
			?: item.seriesId?.let { imageUrl(it, ImageType.PRIMARY, item.seriesPrimaryImageTag, maxWidth) }

	/** Landscape (16:9) image, used for episodes and continue watching rows. */
	fun landscapeUrl(item: BaseItemDto, maxWidth: Int = 640): String? =
		imageUrl(item.id, ImageType.THUMB, item.imageTags?.get(ImageType.THUMB), maxWidth)
			?: item.parentThumbItemId?.let { imageUrl(it, ImageType.THUMB, item.parentThumbImageTag, maxWidth) }
			?: episodeStillUrl(item, maxWidth)
			?: backdropUrl(item, maxWidth)

	private fun episodeStillUrl(item: BaseItemDto, maxWidth: Int): String? =
		if (item.type == BaseItemKind.EPISODE) imageUrl(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY), maxWidth)
		else null

	/** Transparent title logo, falls back to the series logo for episodes and seasons. */
	fun logoUrl(item: BaseItemDto, maxWidth: Int = 800): String? =
		imageUrl(item.id, ImageType.LOGO, item.imageTags?.get(ImageType.LOGO), maxWidth)
			?: item.parentLogoItemId?.let { imageUrl(it, ImageType.LOGO, item.parentLogoImageTag, maxWidth) }

	fun backdropUrl(item: BaseItemDto, maxWidth: Int = 1920): String? =
		item.backdropImageTags?.firstOrNull()?.let { imageUrl(item.id, ImageType.BACKDROP, it, maxWidth) }
			?: item.parentBackdropItemId?.let { parentId ->
				imageUrl(parentId, ImageType.BACKDROP, item.parentBackdropImageTags?.firstOrNull(), maxWidth)
			}

	private companion object {
		fun deviceName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
			?.takeIf { it.isNotBlank() && it != "localhost" }
			?: "Steam Frame"
	}
}
