/*
 * Copyright 2026 Punch Through Design LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.punchthrough.blestarterappandroid.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val contactAddress: Int,   // dirección del nodo remoto (quién envió o recibió)
    val content: String,       // el texto del mensaje (siempre en claro — se descifra antes de guardar)
    val timestamp: Long,       // System.currentTimeMillis() en el momento del envío/recepción
    val isOutgoing: Boolean,   // true = lo enviamos nosotros, false = lo recibimos

    // 0 = sin cifrar, 1 = grupo, 2 = E2E node-to-node
    val encType: Int = 0,

    // Para mensajes del canal público: address del nodo que lo envió (0 = desconocido/saliente)
    @ColumnInfo(defaultValue = "0")
    val senderAddress: Int = 0
)