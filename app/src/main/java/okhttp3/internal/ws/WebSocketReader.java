/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.ws;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.concurrent.TimeUnit;

import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;

import static java.lang.Integer.toHexString;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_FIN;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV1;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV2;
import static okhttp3.internal.ws.WebSocketProtocol.B0_FLAG_RSV3;
import static okhttp3.internal.ws.WebSocketProtocol.B0_MASK_OPCODE;
import static okhttp3.internal.ws.WebSocketProtocol.B1_FLAG_MASK;
import static okhttp3.internal.ws.WebSocketProtocol.B1_MASK_LENGTH;
import static okhttp3.internal.ws.WebSocketProtocol.CLOSE_NO_STATUS_CODE;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_BINARY;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTINUATION;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_CLOSE;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PING;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_CONTROL_PONG;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_FLAG_CONTROL;
import static okhttp3.internal.ws.WebSocketProtocol.OPCODE_TEXT;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_BYTE_MAX;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_LONG;
import static okhttp3.internal.ws.WebSocketProtocol.PAYLOAD_SHORT;
import static okhttp3.internal.ws.WebSocketProtocol.toggleMask;

/**
 * An <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a>-compatible WebSocket frame reader.
 * <p>
 * <p>This class is not thread safe.
 */
final class WebSocketReader {
    public interface FrameCallback {
        void onReadMessage(String text) throws IOException;

        void onReadMessage(ByteString bytes) throws IOException;

        void onReadPing(ByteString buffer);

        void onReadPong(ByteString buffer);

        void onReadClose(int code, String reason);
    }

    final boolean isClient;
    final BufferedSource source;
    final FrameCallback frameCallback;

    boolean closed;

    // Stateful data about the current frame.
    int opcode;
    long frameLength;
    boolean isFinalFrame;
    boolean isControlFrame;

    private final Buffer controlFrameBuffer = new Buffer();
    private final Buffer messageFrameBuffer = new Buffer();

    private final byte[] maskKey;
    private final Buffer.UnsafeCursor maskCursor;

    WebSocketReader(boolean isClient, BufferedSource source, FrameCallback frameCallback) {
        if (source == null) throw new NullPointerException("source == null");
        if (frameCallback == null) throw new NullPointerException("frameCallback == null");
        this.isClient = isClient;
        this.source = source;
        this.frameCallback = frameCallback;

        // Masks are only a concern for server writers.
        maskKey = isClient ? null : new byte[4];
        maskCursor = isClient ? null : new Buffer.UnsafeCursor();
    }

    /**
     * Process the next protocol frame.
     * <p>
     * <ul>
     * <li>If it is a control frame this will result in a single call to {@link FrameCallback}.
     * <li>If it is a message frame this will result in a single call to {@link
     * FrameCallback#onReadMessage}. If the message spans multiple frames, each interleaved
     * control frame will result in a corresponding call to {@link FrameCallback}.
     * </ul>
     */
    void processNextFrame() throws IOException {
        //先读取Header
        readHeader();
        //是控制帧要先读取
        if (isControlFrame) {
            readControlFrame();
        } else {
            readMessageFrame();
        }
    }

    private void readHeader() throws IOException {
        if (closed) throw new IOException("closed");

        //读的第一个字节是同步的不计超时时间的
        int b0;
        long timeoutBefore = source.timeout().timeoutNanos();
        source.timeout().clearTimeout();
        try {
            b0 = source.readByte() & 0xff;
        } finally {
            source.timeout().timeout(timeoutBefore, TimeUnit.NANOSECONDS);
        }

        opcode = b0 & B0_MASK_OPCODE;
        isFinalFrame = (b0 & B0_FLAG_FIN) != 0;
        isControlFrame = (b0 & OPCODE_FLAG_CONTROL) != 0;

        // 控制帧必须是最终帧（不能包含延续）。
        if (isControlFrame && !isFinalFrame) {
            throw new ProtocolException("Control frames must be final.");
        }

        boolean reservedFlag1 = (b0 & B0_FLAG_RSV1) != 0;
        boolean reservedFlag2 = (b0 & B0_FLAG_RSV2) != 0;
        boolean reservedFlag3 = (b0 & B0_FLAG_RSV3) != 0;
        if (reservedFlag1 || reservedFlag2 || reservedFlag3) {
            // 保留标志用于我们目前不支持的扩展。
            throw new ProtocolException("Reserved flags are unsupported.");
        }

        int b1 = source.readByte() & 0xff;

        boolean isMasked = (b1 & B1_FLAG_MASK) != 0;
        if (isMasked == isClient) {
            // Masked payloads must be read on the server. Unmasked payloads must be read on the client.
            throw new ProtocolException(isClient
                    ? "Server-sent frames must not be masked."
                    : "Client-sent frames must be masked.");
        }

        // Get frame length, optionally reading from follow-up bytes if indicated by special values.
        //获取帧长度，如果由特殊值指示，则可选择从后续字节读取。
        frameLength = b1 & B1_MASK_LENGTH;
        if (frameLength == PAYLOAD_SHORT) {
            frameLength = source.readShort() & 0xffffL; // Value is unsigned.
        } else if (frameLength == PAYLOAD_LONG) {
            frameLength = source.readLong();
            if (frameLength < 0) {
                throw new ProtocolException(
                        "Frame length 0x" + Long.toHexString(frameLength) + " > 0x7FFFFFFFFFFFFFFF");
            }
        }

        if (isControlFrame && frameLength > PAYLOAD_BYTE_MAX) {
            throw new ProtocolException("Control frame must be less than " + PAYLOAD_BYTE_MAX + "B.");
        }

        if (isMasked) {
            // Read the masking key as bytes so that they can be used directly for unmasking.
            //以字节读取屏蔽键，以便它们可以直接用于取消屏蔽。
            source.readFully(maskKey);
        }
    }

    private void readControlFrame() throws IOException {
        if (frameLength > 0) {
            source.readFully(controlFrameBuffer, frameLength);

            if (!isClient) {
                controlFrameBuffer.readAndWriteUnsafe(maskCursor);
                maskCursor.seek(0);
                toggleMask(maskCursor, maskKey);
                maskCursor.close();
            }
        }

        switch (opcode) {
            case OPCODE_CONTROL_PING:
                frameCallback.onReadPing(controlFrameBuffer.readByteString());
                break;
            case OPCODE_CONTROL_PONG:
                frameCallback.onReadPong(controlFrameBuffer.readByteString());
                break;
            case OPCODE_CONTROL_CLOSE:
                int code = CLOSE_NO_STATUS_CODE;
                String reason = "";
                long bufferSize = controlFrameBuffer.size();
                if (bufferSize == 1) {
                    throw new ProtocolException("Malformed close payload length of 1.");
                } else if (bufferSize != 0) {
                    code = controlFrameBuffer.readShort();
                    reason = controlFrameBuffer.readUtf8();
                    String codeExceptionMessage = WebSocketProtocol.closeCodeExceptionMessage(code);
                    if (codeExceptionMessage != null)
                        throw new ProtocolException(codeExceptionMessage);
                }
                //回调给RealWebSocket
                frameCallback.onReadClose(code, reason);
                closed = true;
                break;
            default:
                throw new ProtocolException("Unknown control opcode: " + toHexString(opcode));
        }
    }

    private void readMessageFrame() throws IOException {
        int opcode = this.opcode;
        if (opcode != OPCODE_TEXT && opcode != OPCODE_BINARY) {
            throw new ProtocolException("Unknown opcode: " + toHexString(opcode));
        }

        readMessage();

        //回调 FrameCallback 将读取的内容通知出去
        if (opcode == OPCODE_TEXT) {
            frameCallback.onReadMessage(messageFrameBuffer.readUtf8());
        } else {
            frameCallback.onReadMessage(messageFrameBuffer.readByteString());
        }
    }

    /**
     * Read headers and process any control frames until we reach a non-control frame.
     * 读取标题并处理任何控制帧，直到达到非控制帧
     */
    private void readUntilNonControlFrame() throws IOException {
        while (!closed) {
            readHeader();
            if (!isControlFrame) {
                break;
            }
            readControlFrame();
        }
    }

    /**
     * Reads a message body into across one or more frames. Control frames that occur between
     * fragments will be processed. If the message payload is masked this will unmask as it's being
     * processed.
     * <p>
     * 通过一个或多个帧读取消息正文。 处理片段之间发生的控制帧。
     * 如果消息有效负载被屏蔽，则在处理它时将取消屏蔽。
     */
    private void readMessage() throws IOException {
        while (true) {
            if (closed) throw new IOException("closed");

            if (frameLength > 0) {
                source.readFully(messageFrameBuffer, frameLength);

                if (!isClient) {
                    messageFrameBuffer.readAndWriteUnsafe(maskCursor);
                    maskCursor.seek(messageFrameBuffer.size() - frameLength);
                    toggleMask(maskCursor, maskKey);
                    maskCursor.close();
                }
            }

            if (isFinalFrame) break; // We are exhausted and have no continuations.

            readUntilNonControlFrame();
            if (opcode != OPCODE_CONTINUATION) {
                throw new ProtocolException("Expected continuation opcode. Got: " + toHexString(opcode));
            }
        }
    }
}
