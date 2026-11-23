package org.rhythmeta.maimaid.ui.song

/**
 * Package flavour offered by the chart-preview share action.
 *
 * Both variants are the same STORE-only zip: `.adx` only swaps the file suffix, because maimai
 * chart tooling expects that name for an importable package.
 */
enum class ChartPackageFormat(
    /** Suffix of the shared file, without the leading dot. */
    val fileSuffix: String,
    /** MIME type reported to the share sheet. An `.adx` is a zip, but its name is what receivers act on. */
    val mimeType: String,
) {
    Zip("zip", "application/zip"),
    Adx("adx", "application/octet-stream"),
}
