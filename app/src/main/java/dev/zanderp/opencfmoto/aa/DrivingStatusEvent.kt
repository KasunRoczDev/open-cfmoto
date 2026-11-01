// Ported from headunit-revived (AGPLv3): aap/protocol/messages/DrivingStatusEvent.kt
package dev.zanderp.opencfmoto.aa

import com.google.protobuf.Message
import dev.zanderp.opencfmoto.aa.proto.Sensors

/** @param status DrivingStatusData.Status bits (0 = unrestricted; see [DrivingStatusPolicy]). */
class DrivingStatusEvent(status: Int)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(status)) {

    companion object {
        private fun makeProto(status: Int): Message =
            Sensors.SensorBatch.newBuilder()
                .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder().setStatus(status))
                .build()
    }
}
