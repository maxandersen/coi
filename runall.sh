for model in llama3.1 granite3-dense starcoder2; do
    jbang --quiet --fresh -Dquarkus.langchain4j.ollama.chat-model.model-id=$model coi.java
done