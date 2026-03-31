package com.rokid.os.sprite.tts;

import com.rokid.os.sprite.tts.ITtsListener;

interface ITtsServer {
    void playTtsMsg(String message, String utteranceId, ITtsListener listener);
    void stopTtsPlay(String utteranceId);
    void updateTtsParam(String paramsJson);
}
