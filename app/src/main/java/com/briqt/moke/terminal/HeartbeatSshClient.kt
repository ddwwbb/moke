package com.briqt.moke.terminal

import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient

/** 长连接使用协议级 IGNORE 心跳；目标与跳板共用此客户端。 */
internal class HeartbeatSshClient : SSHClient(DefaultConfig().apply {
    keepAliveProvider = KeepAliveProvider.HEARTBEAT
}) {
    override fun onConnect() {
        // SSHJ 0.38 在 doKex() 前启动已启用的心跳，且线程立即发送 IGNORE。
        // 默认间隔为 0，先让 connect / connectVia 完成初始 KEX，再启用并启动。
        super.onConnect()
        connection.keepAlive.apply {
            keepAliveInterval = 30
            start()
        }
    }
}
