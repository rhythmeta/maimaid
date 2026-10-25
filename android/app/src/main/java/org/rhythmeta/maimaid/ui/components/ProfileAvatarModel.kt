package org.rhythmeta.maimaid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import org.rhythmeta.maimaid.core.data.PresetAvatarRepository

/**
 * Resolves the Coil model for a profile avatar.
 *
 * Order matches the avatar editor: the locally stored or cropped file first, then the
 * cached preset icon, and only then the remote URL. Preset avatars must resolve through
 * [PresetAvatarRepository.imageFileFor] because their persisted URL can point at an
 * unavailable image host, while the catalog sync has already cached the icon locally.
 */
@Composable
fun rememberProfileAvatarModel(
    avatarPath: String?,
    avatarUrl: String?,
    presetAvatarRepository: PresetAvatarRepository,
): Any? = remember(avatarPath, avatarUrl, presetAvatarRepository) {
    avatarPath
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?: presetAvatarRepository.imageFileFor(avatarUrl)
        ?: avatarUrl?.takeIf(String::isNotBlank)
}
