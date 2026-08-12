package com.sunrich.oms.integration.ftp

import com.sunrich.oms.exception.BadRequestException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayOutputStream

@Service
@ConditionalOnProperty(prefix = "oms.ftp", name = ["enabled"], havingValue = "true")
class FtpStorageService(
    @Value("\${oms.ftp.host}") private val host: String,
    @Value("\${oms.ftp.port}") private val port: Int,
    @Value("\${oms.ftp.username}") private val username: String,
    @Value("\${oms.ftp.password}") private val password: String,
    @Value("\${oms.ftp.base-directory}") private val baseDirectory: String
) {
    fun upload(path: String, file: MultipartFile): String = withClient { client ->
        val remotePath = remotePath(path)
        createDirectories(client, remotePath.substringBeforeLast('/', ""))
        file.inputStream.use { input ->
            if (!client.storeFile(remotePath, input)) throw BadRequestException("FTP upload failed: ${client.replyString}")
        }
        remotePath
    }

    fun download(path: String): ByteArray = withClient { client ->
        val output = ByteArrayOutputStream()
        if (!client.retrieveFile(remotePath(path), output)) throw BadRequestException("FTP file was not found")
        output.toByteArray()
    }

    private fun remotePath(path: String): String {
        val clean = path.replace('\\', '/').trim('/').takeIf { it.isNotBlank() }
            ?: throw BadRequestException("A file path is required")
        if (clean.split('/').any { it == ".." }) throw BadRequestException("Invalid file path")
        return "${baseDirectory.trimEnd('/')}/$clean"
    }

    private fun createDirectories(client: FTPClient, directory: String) {
        var current = ""
        directory.split('/').filter(String::isNotBlank).forEach { part ->
            current += "/$part"
            if (!client.changeWorkingDirectory(current)) client.makeDirectory(current)
        }
    }

    private fun <T> withClient(block: (FTPClient) -> T): T {
        val client = FTPClient()
        try {
            client.connect(host, port)
            if (!client.login(username, password)) throw BadRequestException("FTP authentication failed")
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            return block(client)
        } finally {
            if (client.isConnected) runCatching { client.logout(); client.disconnect() }
        }
    }
}

@RestController
@RequestMapping("/files")
@ConditionalOnProperty(prefix = "oms.ftp", name = ["enabled"], havingValue = "true")
class FtpStorageController(private val storage: FtpStorageService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestParam path: String, @RequestPart file: MultipartFile) =
        mapOf("path" to storage.upload(path, file))

    @GetMapping
    fun download(@RequestParam path: String): ResponseEntity<ByteArrayResource> {
        val bytes = storage.download(path)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${path.substringAfterLast('/')}\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(bytes.size.toLong())
            .body(ByteArrayResource(bytes))
    }
}
