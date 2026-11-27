package com.camasirsarayi.app

import java.text.Normalizer
import java.util.Locale

private val diacritics = Regex("\\p{M}+")
private val separators = Regex("[^\\p{L}\\p{N}]+")

internal fun dormSearchKey(value: String): String = Normalizer
    .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(diacritics, "").replace('ı', 'i')
    .replace(separators, " ").trim()
