package org.rhythmeta.maimaid.core.ml

internal object ScannerNoteCountValidator {
    fun isCompatible(maxDxScore: Int?, sheetTotal: Int?): Boolean {
			return maxDxScore == null || maxDxScore <= 0 || sheetTotal == null || maxDxScore / 3 <= sheetTotal
		}
}
