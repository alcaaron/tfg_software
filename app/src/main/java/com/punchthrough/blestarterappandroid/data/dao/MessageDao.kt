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

package com.punchthrough.blestarterappandroid.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.punchthrough.blestarterappandroid.data.model.Message

@Dao
interface MessageDao {

    // Todos los mensajes de una conversación, ordenados por tiempo
    @Query("SELECT * FROM messages WHERE contactAddress = :address ORDER BY timestamp ASC")
    fun getMessagesForContact(address: Int): LiveData<List<Message>>

    // El último mensaje de cada contacto (para la lista de chats)
    @Query("""
        SELECT * FROM messages 
        WHERE id IN (
            SELECT MAX(id) FROM messages GROUP BY contactAddress
        )
        ORDER BY timestamp DESC
    """)
    fun getLastMessagePerContact(): LiveData<List<Message>>

    @Insert
    suspend fun insert(message: Message)

    @Query("DELETE FROM messages WHERE contactAddress = :address")
    suspend fun deleteConversation(address: Int)
}