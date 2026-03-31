package com.rokid.os.sprite.assist.client;

import com.rokid.os.sprite.assist.basic.AssistMessage;
import com.rokid.os.sprite.assist.basic.RegisterResult;

interface IAssistClient {
    void onRegisterResult(in RegisterResult result);
    boolean onMessageReceive(in AssistMessage message);
    void onDataReceive(String channel, String extra, in byte[] data);
}
