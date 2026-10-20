package org.rhythmeta.maimaid.core.update

enum class AppUpdateFailure {
    Network, InvalidManifest, UnsupportedDevice, Storage, Download, Integrity, Package, Signature, Install,
}
