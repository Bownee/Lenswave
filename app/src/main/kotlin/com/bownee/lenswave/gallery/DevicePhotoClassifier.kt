package com.bownee.lenswave.gallery

object DevicePhotoClassifier {
    fun classify(
        bucketName: String?,
        relativePath: String?,
        ownerPackageName: String?,
        isDownload: Boolean,
    ): DeviceCollection {
        val location = "${bucketName.orEmpty()}/${relativePath.orEmpty()}".lowercase()
        val owner = ownerPackageName.orEmpty().lowercase()
        return when {
            owner.contains("whatsapp") || location.contains("whatsapp") -> DeviceCollection.WHATSAPP
            location.contains("screenshot") -> DeviceCollection.SCREENSHOTS
            isDownload || location.contains("download") -> DeviceCollection.DOWNLOADS
            location.contains("dcim/camera") || bucketName.equals("camera", ignoreCase = true) -> DeviceCollection.CAMERA
            else -> DeviceCollection.OTHER
        }
    }
}
