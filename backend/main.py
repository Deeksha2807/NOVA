"""
NOVA Backend - Main Application
FastAPI server bridging NOVA Android app with Google Gemini.
Supports text chat, Gemini tool-call requests, and tool-result round-trips.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any, List, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from gemini_service import (
    ChatMessagePayload,
    GeminiChatResult,
    GeminiConfigurationError,
    GeminiExecutionError,
    GeminiService,
)
from tools import ToolArgumentValidationError, ToolCallRequest, UnknownToolError

# Explicitly load environment configuration
_BACKEND_DIR = Path(__file__).resolve().parent
_ENV_FILE = _BACKEND_DIR / ".env"
_PROJECT_ENV = _BACKEND_DIR.parent / ".env"

if _ENV_FILE.exists():
    load_dotenv(dotenv_path=_ENV_FILE, override=True)
elif _PROJECT_ENV.exists():
    load_dotenv(dotenv_path=_PROJECT_ENV, override=True)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("nova.backend")

app = FastAPI(
    title="NOVA Personal Agent Backend",
    description="Backend service providing Google Gemini AI intelligence and tool calling for NOVA Android.",
    version="1.0.0",
)

# Enable CORS for Android client connections (emulator 10.0.2.2 or local LAN IP)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Initialize Gemini Service singleton
gemini_service = GeminiService()


# ── Pydantic Request & Response Models ───────────────────────────────────────

class ChatMessage(BaseModel):
    """A single turn in the conversation history."""
    role: str = Field(
        ...,
        description="Sender role: 'user' or 'assistant'",
        examples=["user"],
    )
    content: str = Field(
        ...,
        min_length=1,
        max_length=8000,
        description="Content of the message",
        examples=["Hello NOVA"],
    )


class ChatRequest(BaseModel):
    """Input payload for POST /api/chat."""
    prompt: str = Field(
        ...,
        min_length=1,
        max_length=4000,
        description="The user's spoken or typed prompt for NOVA.",
        examples=["Remember to pick up milk tomorrow."],
    )
    history: List[ChatMessage] = Field(
        default_factory=list,
        description="Prior conversation history for context.",
    )
    system_instruction: Optional[str] = Field(
        default=None,
        description="Optional custom system prompt override.",
    )


class ChatResponse(BaseModel):
    """Output payload from POST /api/chat and POST /api/chat/tool-result."""
    response_type: str = Field(
        default="text_reply",
        description="Distinguishes normal 'text_reply' from an Android 'tool_request'.",
        examples=["text_reply", "tool_request"],
    )
    reply: str = Field(
        default="",
        description="The assistant's spoken / displayed response (or explanatory text).",
    )
    model: str = Field(
        ...,
        description="The Gemini model used.",
    )
    tool_calls: List[ToolCallRequest] = Field(
        default_factory=list,
        description="List of requested validated tool calls to execute on Android.",
    )
    status: str = Field(
        default="success",
        description="Response status indicator.",
    )


class ToolCallPayload(BaseModel):
    """Identifies the tool invocation requested by Gemini."""
    call_id: str = Field(..., description="The unique invocation ID from the previous tool_request.")
    tool_name: str = Field(..., description="Name of the executed tool.")
    arguments: dict[str, Any] = Field(default_factory=dict, description="The arguments that were executed.")


class ToolResultRequest(BaseModel):
    """Input payload for POST /api/chat/tool-result after Android executes a tool."""
    prompt: str = Field(
        ...,
        min_length=1,
        description="The original user prompt that triggered the tool call.",
    )
    tool_call: ToolCallPayload = Field(
        ...,
        description="The tool call payload that was executed.",
    )
    tool_result: dict[str, Any] = Field(
        ...,
        description="The execution result from the Android Room database (or cancellation info).",
        examples=[{"success": True, "note_id": 1}],
    )
    history: List[ChatMessage] = Field(
        default_factory=list,
        description="Conversation history prior to the original prompt.",
    )
    system_instruction: Optional[str] = Field(
        default=None,
        description="Optional custom system prompt override.",
    )


class HealthResponse(BaseModel):
    """Output payload from GET /health."""
    status: str
    gemini_configured: bool
    model: str
    version: str


# ── Endpoints ────────────────────────────────────────────────────────────────

@app.get("/health", response_model=HealthResponse)
def get_health() -> HealthResponse:
    """
    Health check endpoint verifying server operational status
    and whether a Gemini API key is configured.
    """
    is_ready = gemini_service.is_configured
    return HealthResponse(
        status="ok" if is_ready else "degraded",
        gemini_configured=is_ready,
        model=gemini_service.model,
        version="1.0.0",
    )


@app.post("/api/chat", response_model=ChatResponse)
def chat_endpoint(request: ChatRequest) -> ChatResponse:
    """
    Processes a chat prompt through Google Gemini.
    Returns either a direct conversational text reply OR a validated tool execution request for Android.
    """
    cleaned_prompt = request.prompt.strip()
    if not cleaned_prompt:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Prompt cannot be empty or contain only whitespace.",
        )

    history_payload = [
        ChatMessagePayload(role=m.role, content=m.content)
        for m in request.history
    ]

    try:
        result: GeminiChatResult = gemini_service.generate_chat_response(
            prompt=cleaned_prompt,
            history=history_payload,
            system_instruction=request.system_instruction,
        )

        return ChatResponse(
            response_type=result.response_type,
            reply=result.text,
            model=result.model,
            tool_calls=result.tool_calls,
            status="success",
        )

    except (ToolArgumentValidationError, UnknownToolError) as tool_err:
        logger.warning("Gemini generated invalid tool call: %s", str(tool_err))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="The AI model generated an unexecutable or invalid tool request.",
        ) from None

    except GeminiConfigurationError as err:
        logger.warning("Chat request failed due to missing configuration: %s", str(err))
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Gemini API is not configured on the backend. Please configure GEMINI_API_KEY.",
        ) from None

    except GeminiExecutionError as err:
        logger.error("Chat request failed during Gemini execution: %s", str(err))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Failed to generate response from AI service. Please try again.",
        ) from None

    except Exception as err:
        logger.exception("Unexpected error in chat endpoint")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Internal server error processing request.",
        ) from None


@app.post("/api/chat/tool-result", response_model=ChatResponse)
def tool_result_endpoint(request: ToolResultRequest) -> ChatResponse:
    """
    Follow-up endpoint for Android to submit the local execution result of a tool.
    Gemini incorporates the result and generates the final user-facing response.
    """
    history_payload = [
        ChatMessagePayload(role=m.role, content=m.content)
        for m in request.history
    ]

    try:
        result: GeminiChatResult = gemini_service.continue_chat_with_tool_result(
            prompt=request.prompt,
            tool_name=request.tool_call.tool_name,
            tool_args=request.tool_call.arguments,
            tool_result=request.tool_result,
            history=history_payload,
            system_instruction=request.system_instruction,
            call_id=request.tool_call.call_id,
        )

        return ChatResponse(
            response_type=result.response_type,
            reply=result.text,
            model=result.model,
            tool_calls=[],
            status="success",
        )

    except GeminiConfigurationError as err:
        logger.warning("Tool-result follow-up failed due to missing configuration: %s", str(err))
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Gemini API is not configured on the backend. Please configure GEMINI_API_KEY.",
        ) from None

    except GeminiExecutionError as err:
        logger.error("Tool-result follow-up failed during Gemini execution: %s", str(err))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Failed to generate final response from AI service. Please try again.",
        ) from None

    except Exception as err:
        logger.exception("Unexpected error in tool-result endpoint")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Internal server error processing tool result.",
        ) from None


# ── Local Runner ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn

    host = os.getenv("HOST", "0.0.0.0")
    port = int(os.getenv("PORT", "8000"))
    logger.info("Starting NOVA Backend on %s:%d", host, port)
    uvicorn.run("main:app", host=host, port=port, reload=True)
