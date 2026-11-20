package io.quack.common.model;

/**
 * 消息类型。
 *
 * <p>骨架阶段只把 TEXT 真正跑通；IMAGE / FILE 在协议与表结构里预留了位置，
 * 后续接对象存储时，{@code content} 存文件 URL 或 fileId 即可。
 */
public enum MsgType {
    TEXT,
    IMAGE,
    FILE,
    /** 视频消息，content = url|封面url|时长ms|宽|高|文件名 */
    VIDEO,
    /** 系统消息，如“XX 加入了群聊”，由服务端插入，客户端居中展示 */
    SYSTEM,
    /** 礼物消息，content = giftId|名称|图标 */
    GIFT
}
