package com.rokid.os.sprite.assist.server;

import android.graphics.Bitmap;
import com.rokid.os.sprite.assist.client.IAssistClient;

interface IAssistServer {
    void registerClient(String packageName, IAssistClient client);
    void unRegisterClient(String packageName);
    void controlMsgJson(String packageName, String json);
    void scanQrCodeBitmap(in Bitmap bitmap);
    void scanQrCodeBmList(in List<Bitmap> bitmaps);
}
