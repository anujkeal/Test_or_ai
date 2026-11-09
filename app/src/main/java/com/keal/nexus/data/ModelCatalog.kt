package com.keal.nexus.data

data class ModelSpec(
    val id: String,
    val name: String,
    val publisher: String,
    val parameters: String,
    val quantization: String,
    val sizeLabel: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String? = null,
    val description: String
)

object ModelCatalog {
    val models = listOf(
        ModelSpec(
            id = "llama-3.2-3b-q4",
            name = "Llama 3.2 3B Instruct",
            publisher = "Meta · GGUF by bartowski",
            parameters = "3B",
            quantization = "Q4_K_M",
            sizeLabel = "~2.02 GB",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            description = "3B instruction model. NEXUS verifies the downloaded file with SHA-256 before making it available."
        ),
        ModelSpec(
            id = "llama-3.2-1b-q4",
            name = "Llama 3.2 1B Instruct",
            publisher = "Meta · GGUF by bartowski",
            parameters = "1B",
            quantization = "Q4_K_M",
            sizeLabel = "~0.81 GB",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            description = "Compact instruction model for lighter devices. Review its model card and licence before use."
        ),
        ModelSpec(
            id = "qwen2.5-1.5b-q4",
            name = "Qwen2.5 1.5B Instruct",
            publisher = "Qwen · GGUF by bartowski",
            parameters = "1.5B",
            quantization = "Q4_K_M",
            sizeLabel = "~0.99 GB",
            fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            sha256 = "1adf0b11065d8ad2e8123ea110d1ec956dab4ab038eab665614adba04b6c3370",
            description = "Balanced small instruct model and a practical first download for testing."
        ),
        ModelSpec(
            id = "qwen2.5-3b-q4",
            name = "Qwen2.5 3B Instruct",
            publisher = "Qwen · GGUF by bartowski",
            parameters = "3B",
            quantization = "Q4_K_M",
            sizeLabel = "~1.93 GB",
            fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/688ff94db654f6bca34247beb205071e8be0ca60/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            sha256 = "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94",
            description = "Larger local model with higher memory requirements. Check available RAM before loading."
        )
    )
}
