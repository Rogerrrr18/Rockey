package com.rokid.os.sprite.assist.basic

import android.os.Parcel
import android.os.Parcelable

class RegisterResult() : Parcelable {
    private constructor(parcel: Parcel) : this()

    override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<RegisterResult> {
        override fun createFromParcel(parcel: Parcel): RegisterResult = RegisterResult(parcel)

        override fun newArray(size: Int): Array<RegisterResult?> = arrayOfNulls(size)
    }
}
