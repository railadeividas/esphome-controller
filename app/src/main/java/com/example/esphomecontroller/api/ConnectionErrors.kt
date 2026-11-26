package com.example.esphomecontroller.api

import java.io.IOException

/** Failures that retrying cannot fix; the user has to change the device settings. */
open class SetupException(message: String) : IOException(message)

class EncryptionRequiredException : SetupException("The device uses encryption; enter its encryption key")
class EncryptionNotEnabledException(detail: String = "") : SetupException("The device does not use encryption" + if (detail.isBlank()) "" else " ($detail)")
class KeyRejectedException : SetupException("The device rejected the encryption key")
class CredentialsRejectedException : SetupException("The web server rejected the username or password")
