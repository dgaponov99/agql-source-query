/*
 * Copyright (c) 2022 Asynchronous Game Query Library
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

package com.ibasco.agql.protocols.valve.source.query.info;

import com.ibasco.agql.core.NettyChannelContext;
import com.ibasco.agql.core.util.Netty;
import com.ibasco.agql.protocols.valve.source.query.common.handlers.SourceQueryAuthDecoder;
import com.ibasco.agql.protocols.valve.source.query.common.packets.SourceQuerySinglePacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static com.ibasco.agql.protocols.valve.source.query.SourceQuery.*;

/**
 * <p>GoldSourceQueryInfoDecoder class.</p>
 *
 * @author Rafael Luis Ibasco
 */
@SuppressWarnings({"DuplicatedCode", "SameParameterValue"})
public class GoldSourceQueryInfoDecoder extends SourceQueryAuthDecoder<SourceQueryInfoRequest> {

    private static final Function<ByteBuf, String> READ_ASCII_BYTE_STR = buf -> buf.readCharSequence(1, StandardCharsets.US_ASCII).toString();

    private static final Function<ByteBuf, String> PEEK_ASCII_BYTE_STR = buf -> new String(new byte[] {buf.getByte(buf.readerIndex())}, StandardCharsets.US_ASCII);

    private static final Function<Byte, Boolean> IS_VAC = byteVal -> byteVal == 1;

    private static final Function<Byte, Boolean> IS_PRIVATE_SERVER = byteVal -> byteVal != 0;

    /**
     * <p>Constructor for GoldSourceQueryInfoDecoder.</p>
     */
    public GoldSourceQueryInfoDecoder() {
        super(SourceQueryInfoRequest.class, GOLD_SOURCE_QUERY_INFO_RES);
    }

    /** {@inheritDoc} */
    @Override
    protected Object decodeQueryPacket(ChannelHandlerContext ctx, SourceQueryInfoRequest request, SourceQuerySinglePacket packet) throws Exception {
        NettyChannelContext context = NettyChannelContext.getContext(ctx.channel());

        ByteBuf buf = packet.content();

        final SourceServer info = new SourceServer();

        info.setAddress(context.properties().remoteAddress());

        //NOTE: Some servers return an empty response. If this is the case, we skip the decoding process and simply return SourceServer instance
        if (buf.isReadable()) {
            debug("Attempting to decode A2S_INFO response (Reader Index: {}, Readable bytes: {})", buf.readerIndex(), buf.readableBytes());

            //NOTE: read and skip address field
            decodeField("address", buf, Netty::readString, null);
            decodeField("name", buf, Netty::readString, info::setName);
            decodeField("mapName", buf, Netty::readString, info::setMapName);
            decodeField("gameDirectory", buf, Netty::readString, info::setGameDirectory);
            decodeField("gameDescription", buf, Netty::readString, info::setGameDescription);
            decodeField("playerCount", buf, buf::readUnsignedByte, info::setNumOfPlayers, Short::intValue);
            decodeField("maxPlayerCount", buf, buf::readUnsignedByte, info::setMaxPlayers, Short::intValue);
            decodeField("protocol", buf, buf::readUnsignedByte, info::setNetworkVersion, Short::byteValue);

            decodeField("isSourceTvProxy", buf, PEEK_ASCII_BYTE_STR, info::setSourceTvProxy, "p"::equalsIgnoreCase);
            decodeField("isDedicated", buf, READ_ASCII_BYTE_STR, info::setDedicated, "d"::equalsIgnoreCase); //D = dedicated, L = non-dedicated, P = source tv proxy
            decodeField("operatingSystem", buf, READ_ASCII_BYTE_STR, info::setOperatingSystem, String::toLowerCase); //L = linux, W = windows
            decodeField("isPrivateServer", buf, buf::readByte, info::setPrivateServer, IS_PRIVATE_SERVER); //0 = public, 1 = private

            int mod = buf.readByte();
            if (mod == 1) {
                decodeField("link", buf, Netty::readString, null);
                decodeField("downloadLink", buf, Netty::readString, null);
                decodeField("NULL", buf, buf::readByte, null);
                decodeField("gameVersion", buf, buf::readLongLE, info::setGameVersion);
                decodeField("spaceSize", buf, buf::readLongLE, null);
                decodeField("gameType", buf, buf::readByte, null);
                decodeField("DLL", buf, buf::readByte, null);
            }

            decodeField("isSecure", buf, buf::readByte, info::setSecure, IS_VAC); //0 = unsecured, 1 = secured
            decodeField("botCount", buf, buf::readUnsignedByte, info::setNumOfBots, Short::intValue);

        } else {
            debug("Received an empty INFO response");
        }
        return new SourceQueryInfoResponse(info);
    }
}
