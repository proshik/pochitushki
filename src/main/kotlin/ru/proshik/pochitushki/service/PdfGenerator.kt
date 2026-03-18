package ru.proshik.pochitushki.service

interface PdfGenerator {

    fun generatePdf(url: String): ByteArray
}
