package io.quack.common.protocol;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;

/**
 * 统一协议信封。
 *
 * <p>所有 WebSocket 文本帧都是这个结构序列化后的 JSON：
 * <pre>{@code
 * {"cmd":"msg.send","seq":12,"code":0,"msg":null,"data":{"to":"u2","content":"hi"}}
 * }</pre>
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code cmd}  命令字，见 {@link Cmd}</li>
 *   <li>{@code seq}  客户端自增序号；服务端在响应中原样带回，用于请求-响应配对；推送消息固定为 0</li>
 *   <li>{@code code} 0 表示成功，非 0 为错误码</li>
 *   <li>{@code msg}  错误描述</li>
 *   <li>{@code data} 业务负载，**直接内联为 JSON 对象**（不再二次转义成字符串）</li>
 * </ul>
 *
 * <p>兼容性：解析时 `data` 既接受内联对象，也接受旧格式的“转义 JSON 字符串”；
 * 内部仍以 JSON 文本保存，业务层用 {@link #dataAs(Class)} 延迟解析。
 */
public record Envelope(
        String cmd,
        long seq,
        int code,
        String msg,
        @JsonSerialize(using = DataSerializer.class)
        @JsonDeserialize(using = DataDeserializer.class)
        String data
) {

    public static final int CODE_OK = 0;

    /** 构建成功响应 / 推送 */
    public static Envelope of(String cmd, long seq, Object data) {
        return new Envelope(cmd, seq, CODE_OK, null, data == null ? null : JsonCodec.toJson(data));
    }

    /** 构建失败响应 */
    public static Envelope error(String cmd, long seq, int code, String message) {
        return new Envelope(cmd, seq, code, message, null);
    }

    /** 把 data 反序列化成指定类型 */
    public <T> T dataAs(Class<T> type) {
        if (data == null || data.isBlank()) {
            return null;
        }
        return JsonCodec.fromJson(data, type);
    }

    /** 序列化：把内部 JSON 文本原样写进信封，避免 "data":"{\"a\":1}" 的双重编码 */
    public static final class DataSerializer extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                generator.writeNull();
            } else {
                generator.writeRawValue(value);
            }
        }
    }

    /** 反序列化：内联对象 / 旧版转义字符串都接受 */
    public static final class DataDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            if (node == null || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.asText() : node.toString();
        }
    }
}
