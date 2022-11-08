/*
 * Copyright 2022 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import com.google.common.primitives.Longs;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.Protocol;
import org.traccar.helper.BitUtil;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class G1rusProtocolDecoder extends BaseProtocolDecoder {
    public G1rusProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    public static final int HEAD_TAIL = 0xF8;

    public static final int MSG_HEARTBEAT = 0;
    public static final int MSG_REGULAR = 1;
    public static final int MSG_MIXED = 4;

    private String readString(ByteBuf buf) {
        int length = buf.readUnsignedByte() & 0xF;
        return buf.readCharSequence(length, StandardCharsets.US_ASCII).toString();
    }

    private int getADValue(int rawValue) {
        final int AD_MIN = -10;
        final int AD_MAX = 100;

        return rawValue * (AD_MAX - AD_MIN) / 4096 + AD_MIN;
    }

    private Position decodeRegular(DeviceSession deviceSession, ByteBuf buf, int type) {

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        position.setTime(new Date((buf.readUnsignedInt() + 946684800) * 1000L));

        if (BitUtil.check(type, 6)) {
            position.set(Position.KEY_EVENT, buf.readUnsignedByte());
        }

        int dataMask = buf.readUnsignedShort();

        if (BitUtil.check(dataMask, 0)) {
            buf.readUnsignedByte(); // length
            readString(buf); // device name
            position.set(Position.KEY_VERSION_FW, readString(buf));
            position.set(Position.KEY_VERSION_HW, readString(buf));
        }

        if (BitUtil.check(dataMask, 1)) {
            buf.readUnsignedByte(); // length
            int locationMask = buf.readUnsignedShort();
            if (BitUtil.check(locationMask, 0)) {
                int validity = buf.readUnsignedByte();
                position.set(Position.KEY_SATELLITES, BitUtil.to(validity, 5));
                position.setValid(BitUtil.between(validity, 5, 7) == 2);
            }
            if (BitUtil.check(locationMask, 1)) {
                position.setLatitude(buf.readInt() / 1000000.0);
                position.setLongitude(buf.readInt() / 1000000.0);
            }
            if (BitUtil.check(locationMask, 2)) {
                position.setSpeed(buf.readUnsignedShort());
            }
            if (BitUtil.check(locationMask, 3)) {
                position.setCourse(buf.readUnsignedShort());
            }
            if (BitUtil.check(locationMask, 4)) {
                position.setAltitude(buf.readShort());
            }
            if (BitUtil.check(locationMask, 5)) {
                position.set(Position.KEY_HDOP, buf.readUnsignedShort());
            }
            if (BitUtil.check(locationMask, 6)) {
                position.set(Position.KEY_VDOP, buf.readUnsignedShort());
            }
        }

        if (BitUtil.check(dataMask, 2)) {
            buf.skipBytes(buf.readUnsignedByte());
        }

        if (BitUtil.check(dataMask, 3)) {
            buf.skipBytes(buf.readUnsignedByte());
        }

        if (BitUtil.check(dataMask, 4)) {
            final int ADC_DATA_MASK = 0b0000111111111111;

            buf.readUnsignedByte(); // length
            position.set(Position.KEY_POWER, getADValue(buf.readUnsignedShort() & ADC_DATA_MASK));
            position.set(Position.KEY_BATTERY, getADValue(buf.readUnsignedShort() & ADC_DATA_MASK));
            position.set(Position.KEY_DEVICE_TEMP, getADValue(buf.readUnsignedShort() & ADC_DATA_MASK));
        }

        if (BitUtil.check(dataMask, 5)) {
            buf.skipBytes(buf.readUnsignedByte());
        }

        if (BitUtil.check(dataMask, 7)) {
            buf.skipBytes(buf.readUnsignedByte());
        }

        if (BitUtil.check(dataMask, 1)) {
            return position;
        } else {
            /* NOTE: if device doesn't send GPS data, rest shouldn't be reported as position.
             * But we still parse the thing in order to get correct offset for the following
             * data in batch (if it exists). */
            return null;
        }
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        if (buf.readUnsignedByte() != HEAD_TAIL) {
            return null;
        }

        buf.skipBytes(1); /* skip version */

        int type = buf.readUnsignedByte();
        byte[] imei = new byte[8];
        buf.readBytes(imei, 0, 7);

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress,
                String.valueOf(Longs.fromByteArray(imei)));
        if (deviceSession == null) {
            return null;
        }

        if (BitUtil.to(type, 6) == MSG_REGULAR) {

            return decodeRegular(deviceSession, buf, type);

        } else if (BitUtil.to(type, 6) == MSG_MIXED) {

            List<Position> positions = new LinkedList<>();
            while (buf.readableBytes() > 5) {
                int length = buf.readUnsignedShort();
                int subtype = buf.readUnsignedByte();
                if (BitUtil.to(subtype, 6) == MSG_REGULAR) {
                    Position position = decodeRegular(deviceSession, buf, subtype);
                    if (position != null) {
                        positions.add(position);
                    }
                } else {
                    buf.skipBytes(length);
                }
            }
            return positions.isEmpty() ? null : positions;

        }

        buf.skipBytes(2);
        buf.readUnsignedByte(); // tail

        return null;

    }

}
