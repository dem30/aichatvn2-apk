#!/bin/bash

echo "🧹 Cleaning up AIChatVN2 unused files..."

cd ~/duan

rm -rf app/src/main/java/com/aichatvn/agent/core/heartbeat/
rm -rf app/src/main/java/com/aichatvn/agent/core/telemetry/
rm -rf app/src/main/java/com/aichatvn/agent/core/processor/
rm -rf app/src/main/java/com/aichatvn/agent/core/conversation/
rm -rf app/src/main/java/com/aichatvn/agent/services/
rm -rf app/src/main/java/com/aichatvn/agent/receivers/
rm -rf app/src/main/java/com/aichatvn/agent/workers/

rm -f app/src/main/java/com/aichatvn/agent/core/camera/CameraEngine.kt
rm -f app/src/main/java/com/aichatvn/agent/core/camera/SnapshotEngine.kt
rm -f app/src/main/java/com/aichatvn/agent/core/camera/RtspStreamEngine.kt
rm -f app/src/main/java/com/aichatvn/agent/core/camera/EngineFactory.kt
rm -f app/src/main/java/com/aichatvn/agent/core/camera/CameraFrame.kt
rm -f app/src/main/java/com/aichatvn/agent/core/AgentCore.kt
rm -f app/src/main/java/com/aichatvn/agent/core/AgentResponse.kt
rm -f app/src/main/java/com/aichatvn/agent/core/EventBus.kt

rm -f app/src/main/java/com/aichatvn/agent/core/plugin/PluginRegistry.kt
rm -f app/src/main/java/com/aichatvn/agent/core/plugin/PluginManifest.kt
rm -f app/src/main/java/com/aichatvn/agent/core/plugin/PluginEventBus.kt
rm -f app/src/main/java/com/aichatvn/agent/core/plugin/RuleIntentResolver.kt
rm -f app/src/main/java/com/aichatvn/agent/core/plugin/ParameterValueExtractor.kt
rm -f app/src/main/java/com/aichatvn/agent/core/plugin/RuleResult.kt

rm -f app/src/main/java/com/aichatvn/agent/plugins/SimpleLightPlugin.kt

echo "✅ Cleanup completed!"

# Hiển thị file còn lại
echo ""
echo "📁 Remaining files:"
ls -la app/src/main/java/com/aichatvn/agent/



