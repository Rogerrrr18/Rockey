package com.rokid.os.sprite.assist.basic

import android.os.Parcel
import android.os.Parcelable

class AssistMessage(
    var messageId: Long = 0L,
    var packageName: String? = null,
    var infoType: String? = null,
    var time: Long = 0L,
    var message: String? = null,
    var notifyAll: Boolean = false,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        messageId = parcel.readLong(),
        packageName = parcel.readString(),
        infoType = parcel.readString(),
        time = parcel.readLong(),
        message = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(messageId)
        parcel.writeString(packageName)
        parcel.writeString(infoType)
        parcel.writeLong(time)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String {
        return "AssistMessage(messageId=$messageId, packageName=$packageName, infoType=$infoType, time=$time, message=$message, notifyAll=$notifyAll)"
    }

    companion object CREATOR : Parcelable.Creator<AssistMessage> {
        override fun createFromParcel(parcel: Parcel): AssistMessage = AssistMessage(parcel)

        override fun newArray(size: Int): Array<AssistMessage?> = arrayOfNulls(size)
    }
}
