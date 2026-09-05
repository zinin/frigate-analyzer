package ru.zinin.frigate.analyzer.model.dto

import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

/**
 * One logical object of a recording together with the detections that formed it.
 *
 * [RepresentativeBbox] alone drops membership, and a consumer cannot recover it afterwards:
 * filtering the recording's detections by class folds every same-class object of the scene into
 * one, so two people standing apart end up sharing one confidence and one frame count.
 */
data class BboxCluster(
    val representative: RepresentativeBbox,
    val detections: List<DetectionEntity>,
)
