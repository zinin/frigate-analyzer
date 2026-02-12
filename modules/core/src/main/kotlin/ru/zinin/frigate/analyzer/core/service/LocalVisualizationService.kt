package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.config.properties.LocalVisualizationProperties
import ru.zinin.frigate.analyzer.model.response.Detection
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min

private val logger = KotlinLogging.logger {}

/**
 * Local visualization service that draws detection bounding boxes and labels on images.
 * Replicates Python's DetectionVisualizer functionality using Java2D.
 *
 * This service is thread-safe and can be used concurrently.
 */
@Service
class LocalVisualizationService(
    private val properties: LocalVisualizationProperties,
) {
    companion object {
        // 80-color palette for COCO classes (converted from Python BGR to RGB)
        private val COLOR_PALETTE: List<Color> =
            listOf(
                Color(0, 0, 255),
                Color(0, 255, 0),
                Color(255, 0, 0),
                Color(0, 255, 255),
                Color(255, 0, 255),
                Color(255, 255, 0),
                Color(0, 0, 128),
                Color(0, 128, 0),
                Color(128, 0, 0),
                Color(0, 128, 128),
                Color(128, 0, 128),
                Color(128, 128, 0),
                Color(0, 128, 255),
                Color(128, 0, 255),
                Color(0, 255, 128),
                Color(128, 255, 0),
                Color(255, 0, 128),
                Color(255, 128, 0),
                Color(128, 128, 255),
                Color(128, 255, 128),
                Color(255, 128, 128),
                Color(192, 192, 192),
                Color(64, 64, 64),
                Color(128, 192, 255),
                Color(192, 255, 128),
                Color(255, 128, 192),
                Color(128, 255, 255),
                Color(255, 255, 128),
                Color(255, 128, 255),
                Color(128, 128, 64),
                Color(0, 64, 128),
                Color(128, 64, 0),
                Color(64, 0, 128),
                Color(128, 0, 64),
                Color(64, 128, 0),
                Color(0, 128, 64),
                Color(0, 64, 192),
                Color(64, 192, 0),
                Color(192, 0, 64),
                Color(64, 0, 192),
                Color(192, 64, 0),
                Color(0, 192, 64),
                Color(0, 192, 255),
                Color(192, 255, 0),
                Color(255, 0, 192),
                Color(192, 0, 255),
                Color(255, 192, 0),
                Color(0, 255, 192),
                Color(64, 192, 128),
                Color(128, 192, 64),
                Color(128, 64, 192),
                Color(192, 64, 128),
                Color(192, 128, 64),
                Color(64, 128, 192),
                Color(0, 64, 255),
                Color(64, 255, 0),
                Color(255, 0, 64),
                Color(64, 0, 255),
                Color(255, 64, 0),
                Color(0, 255, 64),
                Color(64, 128, 255),
                Color(128, 255, 64),
                Color(255, 64, 128),
                Color(64, 255, 128),
                Color(255, 128, 64),
                Color(128, 64, 255),
                Color(0, 192, 192),
                Color(192, 192, 0),
                Color(192, 0, 192),
                Color(96, 96, 96),
                Color(160, 160, 160),
                Color(224, 224, 224),
                Color(32, 32, 32),
                Color(192, 224, 255),
                Color(224, 255, 192),
                Color(255, 192, 224),
                Color(192, 255, 255),
                Color(255, 255, 192),
                Color(255, 192, 255),
                Color(32, 96, 160),
            )
    }

    /**
     * Draws detection bounding boxes and labels on the image.
     *
     * @param imageBytes source image as byte array
     * @param detections list of detections to draw
     * @param lineWidth bounding box line thickness
     * @param showLabels whether to display class names
     * @param showConf whether to display confidence scores
     * @param quality JPEG output quality (1-100)
     * @return annotated image as JPEG byte array
     */
    fun visualize(
        imageBytes: ByteArray,
        detections: List<Detection>,
        lineWidth: Int = properties.lineWidth,
        showLabels: Boolean = true,
        showConf: Boolean = true,
        quality: Int = properties.quality,
    ): ByteArray {
        require(imageBytes.isNotEmpty()) { "Image bytes cannot be empty" }
        require(quality in 1..100) { "Quality must be between 1 and 100, got: $quality" }
        require(lineWidth > 0) { "Line width must be positive, got: $lineWidth" }

        logger.debug { "Visualizing ${detections.size} detections on image" }

        val image =
            ImageIO.read(ByteArrayInputStream(imageBytes))
                ?: throw IllegalArgumentException("Failed to decode image")

        val g2d = image.createGraphics()
        try {
            configureGraphics(g2d)
            val fontScale = calculateAdaptiveFontScale(image.height)

            for (detection in detections) {
                drawDetection(g2d, detection, lineWidth, showLabels, showConf, fontScale, image.width, image.height)
            }
        } finally {
            g2d.dispose()
        }

        return encodeToJpeg(image, quality)
    }

    private fun configureGraphics(g2d: Graphics2D) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun calculateAdaptiveFontScale(imageHeight: Int): Float {
        val scaleFactor = imageHeight.toFloat() / properties.referenceHeight
        return max(properties.minFontScale, min(properties.maxFontScale, properties.baseFontScale * scaleFactor))
    }

    private fun getClassColor(classId: Int): Color = COLOR_PALETTE[classId % COLOR_PALETTE.size]

    private fun drawDetection(
        g2d: Graphics2D,
        detection: Detection,
        lineWidth: Int,
        showLabels: Boolean,
        showConf: Boolean,
        fontScale: Float,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        val color = getClassColor(detection.classId)
        val bbox = detection.bbox

        // Normalize and clamp coordinates to image bounds
        val x1 = min(bbox.x1, bbox.x2).toInt().coerceIn(0, imageWidth - 1)
        val y1 = min(bbox.y1, bbox.y2).toInt().coerceIn(0, imageHeight - 1)
        val x2 = max(bbox.x1, bbox.x2).toInt().coerceIn(0, imageWidth)
        val y2 = max(bbox.y1, bbox.y2).toInt().coerceIn(0, imageHeight)

        val width = (x2 - x1).coerceAtLeast(0)
        val height = (y2 - y1).coerceAtLeast(0)

        // Skip invalid bounding boxes
        if (width <= 0 || height <= 0) {
            logger.debug { "Skipping invalid bounding box for ${detection.className}: width=$width, height=$height" }
            return
        }

        // Draw bounding box
        g2d.color = color
        g2d.stroke = BasicStroke(lineWidth.toFloat())
        g2d.drawRect(x1, y1, width, height)

        // Draw label if needed
        if (showLabels || showConf) {
            drawLabelWithBackground(g2d, x1, y1, detection, color, fontScale, showLabels, showConf)
        }
    }

    private fun drawLabelWithBackground(
        g2d: Graphics2D,
        boxX: Int,
        boxY: Int,
        detection: Detection,
        bgColor: Color,
        fontScale: Float,
        showLabels: Boolean,
        showConf: Boolean,
    ) {
        val label = buildLabel(detection, showLabels, showConf)
        val labelPadding = properties.labelPadding
        val fontSize = (properties.baseFontSize * fontScale).toInt().coerceAtLeast(8)
        g2d.font = Font(Font.SANS_SERIF, Font.PLAIN, fontSize)

        val metrics = g2d.fontMetrics
        val textWidth = metrics.stringWidth(label)
        val textHeight = metrics.height
        val ascent = metrics.ascent

        val labelY = max(textHeight + labelPadding * 2, boxY)

        // Draw background rectangle with symmetric padding
        g2d.color = bgColor
        g2d.fillRect(
            boxX,
            labelY - textHeight - labelPadding * 2,
            textWidth + labelPadding * 2,
            textHeight + labelPadding * 2,
        )

        // Draw text centered in the background
        g2d.color = Color.WHITE
        g2d.drawString(label, boxX + labelPadding, labelY - labelPadding - (textHeight - ascent))
    }

    private fun buildLabel(
        detection: Detection,
        showLabels: Boolean,
        showConf: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        if (showLabels) {
            parts.add(detection.className)
        }
        if (showConf) {
            parts.add("%.2f".format(Locale.US, detection.confidence))
        }
        return parts.joinToString(" ")
    }

    private fun encodeToJpeg(
        image: BufferedImage,
        quality: Int,
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()

        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) {
            throw IllegalStateException("No JPEG writer available")
        }

        val writer = writers.next()
        try {
            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality / 100f
                }

            ImageIO.createImageOutputStream(outputStream).use { imageOutputStream ->
                writer.output = imageOutputStream
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }

        return outputStream.toByteArray()
    }
}
