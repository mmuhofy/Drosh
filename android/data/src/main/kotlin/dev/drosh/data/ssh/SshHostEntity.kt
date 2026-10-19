package dev.drosh.data.ssh

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_hosts")
data class SshHostEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "hostname")
    val hostname: String,

    @ColumnInfo(name = "port")
    val port: Int,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "auth_method")
    val authMethod: String,

    @ColumnInfo(name = "key_id")
    val keyId: String?,

    @ColumnInfo(name = "jump_host_id")
    val jumpHostId: String?,

    @ColumnInfo(name = "is_production")
    val isProduction: Boolean,

    /** JSON array of tags; Room has no list column type. */
    @ColumnInfo(name = "tags_json")
    val tagsJson: String,

    @ColumnInfo(name = "last_used_at_ms")
    val lastUsedAtMs: Long,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)
