package com.supercontacts.app.data.repository

object ContactFieldType {
    const val Name = "name"
    const val Phone = "phone"
    const val Telegram = "telegram"
    const val Email = "email"
    const val Nickname = "nickname"
    const val Company = "company"
    const val Note = "note"
    const val Link = "link"
    const val Address = "address"
    const val Address2 = "address_2"
    const val Nationality = "nationality"
    const val Age = "age"
    const val GrindrNick = "grindr_nick"
    const val InstagramUsername = "instagram_username"
    const val FacebookUserId = "facebook_user_id"
    const val Photo = "photo"
    const val Tag = "tag"

    val initialTypes = setOf(
        Name,
        Phone,
        Telegram,
        Email,
        Nickname,
        Company,
        Note,
        Link,
        Address,
        Address2,
        Nationality,
        Age,
        GrindrNick,
        InstagramUsername,
        FacebookUserId,
        Photo,
    )
}
