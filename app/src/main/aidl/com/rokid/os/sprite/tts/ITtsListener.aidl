package com.rokid.os.sprite.tts;

interface ITtsListener {
    void onTtsStart(String utteranceId);
    void onTtsStop(String utteranceId);
}
