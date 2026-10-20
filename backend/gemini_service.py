"""
NOVA Backend - Gemini Service
Integrates with Google Gemini using the official google-genai SDK.
Handles API communication, function/tool declarations, tool validation, and tool-result follow-ups.
Ensures API keys are never leaked in error messages, logs, or responses.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, List, Optional
from dotenv import load_dotenv

from tools import (
    ToolArgumentValidationError,
    ToolCallRequest,
    ToolError,
    UnknownToolError,
    get_notes_gemini_tool,
    validate_tool_call,
)

# Explicitly load environment variables from backend/.env or project root .env
_BACKEND_DIR = Path(__file__).resolve().parent
_ENV_FILE = _BACKEND_DIR / ".env"
_PROJECT_ENV = _BACKEND_DIR.parent / ".env"

def _load_env() -> None:
    if _ENV_FILE.exists():
        load_dotenv(dotenv_path=_ENV_FILE, override=True)
    elif _PROJECT_ENV.exists():
        load_dotenv(dotenv_path=_PROJECT_ENV, override=True)

_load_env()

logger = logging.getLogger("nova.gemini")

DEFAULT_MODEL = "gemini-3.5-flash-lite"
FALLBACK_MODEL = "gemini-3.5-flash"


class GeminiConfigurationError(Exception):
    """Raised when Gemini API key or configuration is missing or invalid."""
    pass


class GeminiExecutionError(Exception):
    """Raised when Gemini API request fails at runtime."""
    pass


@dataclass
class ChatMessagePayload:
    """Represents a message in the conversation history."""
    role: str  # "user" or "assistant" / "model"
    content: str


@dataclass
class GeminiChatResult:
    """Standardized response from the Gemini service."""
    response_type: str  # "text_reply" or "tool_request"
    text: str
    model: str
    tool_calls: List[ToolCallRequest] = field(default_factory=list)
    raw_response: Optional[Any] = None


class GeminiService:
    """
    Service wrapper around Google Gemini using the official `google-genai` SDK.
    Supports tool calling for Android local tools without direct database access.
    """

    def __init__(self, api_key: Optional[str] = None, model: Optional[str] = None):
        self._explicit_api_key = api_key
        self.model = model or os.getenv("GEMINI_MODEL", DEFAULT_MODEL).strip()
        self._client = None
        self._call_cache: dict[str, Any] = {}

        if self.is_configured:
            self._init_client()

    @property
    def api_key(self) -> str:
        """Returns the configured API key, re-checking environment if not explicitly set."""
        if self._explicit_api_key:
            return self._explicit_api_key
        key = os.getenv("GEMINI_API_KEY", "").strip()
        if not key:
            _load_env()
            key = os.getenv("GEMINI_API_KEY", "").strip()
        return key

    @property
    def is_configured(self) -> bool:
        """Returns True if a valid-looking API key is present."""
        key = self.api_key
        return bool(
            key
            and key != "your_gemini_api_key_here"
            and not key.startswith("placeholder")
        )

    def _init_client(self) -> None:
        """Initializes the official google-genai client."""
        key = self.api_key
        if not key:
            self._client = None
            return
        try:
            from google import genai
            self._client = genai.Client(api_key=key)
        except Exception as e:
            logger.error("Failed to initialize google-genai client: %s", str(e))
            self._client = None

    def _get_system_instruction(self, override: Optional[str] = None) -> str:
        """Returns default NOVA system instruction or custom override."""
        return override or (
            "You are NOVA, a personal AI agent running on Android. "
            "You are concise, direct, and conversational. "
            "You help the user manage notes and personal tasks. "
            "When the user asks to create, search, get, update, or delete a note, "
            "use the corresponding tool rather than pretending the action already happened."
        )

    def generate_chat_response(
        self,
        prompt: str,
        history: Optional[List[ChatMessagePayload]] = None,
        system_instruction: Optional[str] = None,
        tools: Optional[List[Any]] = None,
    ) -> GeminiChatResult:
        """
        Sends user prompt and conversation history to Gemini with tool declarations.
        Returns either a direct text response or a validated tool execution request for Android.
        """
        if not self.is_configured:
            raise GeminiConfigurationError(
                "Gemini API key is not configured. Set GEMINI_API_KEY in backend/.env"
            )

        if not self._client:
            self._init_client()
            if not self._client:
                raise GeminiConfigurationError("Could not initialize Gemini SDK client.")

        sys_prompt = self._get_system_instruction(system_instruction)

        try:
            from google.genai import types

            # Attach note tools by default unless explicitly overridden
            active_tools = tools if tools is not None else [get_notes_gemini_tool()]

            config = types.GenerateContentConfig(
                system_instruction=sys_prompt,
                tools=active_tools,
            )

            # Build contents list from history + current prompt
            contents: list[types.Content] = []
            if history:
                for msg in history:
                    role = "user" if msg.role == "user" else "model"
                    contents.append(
                        types.Content(
                            role=role,
                            parts=[types.Part.from_text(text=msg.content)],
                        )
                    )

            # Append current user prompt
            contents.append(
                types.Content(
                    role="user",
                    parts=[types.Part.from_text(text=prompt)],
                )
            )

            # Execute API call with automatic retry on transient 503 / 504
            import time
            max_retries = 3
            response = None
            for attempt in range(max_retries):
                try:
                    response = self._client.models.generate_content(
                        model=self.model,
                        contents=contents,
                        config=config,
                    )
                    break
                except Exception as call_err:
                    err_str = str(call_err)
                    if attempt < max_retries - 1 and ("503" in err_str or "UNAVAILABLE" in err_str or "high demand" in err_str):
                        logger.warning("Gemini 503 demand spike on attempt %d; retrying in 1.5s...", attempt + 1)
                        time.sleep(1.5)
                        continue
                    if self.model != FALLBACK_MODEL and ("504" in err_str or "DEADLINE_EXCEEDED" in err_str or "503" in err_str or "UNAVAILABLE" in err_str or "high demand" in err_str or "429" in err_str or "RESOURCE_EXHAUSTED" in err_str or "quota" in err_str.lower()):
                        logger.warning("Model '%s' had issue (%s). Falling back to '%s'", self.model, err_str[:80], FALLBACK_MODEL)
                        try:
                            response = self._client.models.generate_content(
                                model=FALLBACK_MODEL,
                                contents=contents,
                                config=config,
                            )
                            break
                        except Exception as fallback_err:
                            logger.error("Fallback model '%s' also failed: %s", FALLBACK_MODEL, str(fallback_err))
                            if "429" in str(fallback_err) or "quota" in str(fallback_err).lower():
                                return GeminiChatResult(
                                    response_type=GeminiResponseType.TEXT_REPLY,
                                    reply="I'm temporarily at my Gemini API daily quota limit. Please try again shortly.",
                                    model="quota-limit",
                                )
                            raise
                    if "429" in err_str or "quota" in err_str.lower():
                        return GeminiChatResult(
                            response_type=GeminiResponseType.TEXT_REPLY,
                            reply="I'm temporarily at my Gemini API daily quota limit. Please try again shortly.",
                            model="quota-limit",
                        )
                    raise

            # Check if Gemini requested function/tool calls
            extracted_tool_calls: list[ToolCallRequest] = []
            if response and response.function_calls:
                for fc in response.function_calls:
                    # Treat model output as untrusted input: validate against schema
                    try:
                        validated = validate_tool_call(
                            tool_name=fc.name,
                            raw_args=dict(fc.args) if fc.args else {},
                            call_id=getattr(fc, "id", None),
                        )
                        extracted_tool_calls.append(validated)
                    except ToolError as tool_err:
                        logger.warning("Rejecting invalid Gemini tool call: %s", str(tool_err))
                        raise ToolArgumentValidationError(str(tool_err)) from tool_err

            # Extract any accompanying explanatory text
            response_text = ""
            if response and response.text:
                response_text = response.text.strip()
            elif response and response.candidates:
                candidate = response.candidates[0]
                if candidate.content and candidate.content.parts:
                    part_texts = [
                        p.text for p in candidate.content.parts
                        if hasattr(p, "text") and p.text
                    ]
                    response_text = " ".join(part_texts).strip()

            if extracted_tool_calls:
                for tc in extracted_tool_calls:
                    if tc.call_id and response and response.candidates and response.candidates[0].content:
                        self._call_cache[tc.call_id] = response.candidates[0].content

                return GeminiChatResult(
                    response_type="tool_request",
                    text=response_text,
                    model=self.model,
                    tool_calls=extracted_tool_calls,
                    raw_response=response,
                )
            else:
                return GeminiChatResult(
                    response_type="text_reply",
                    text=response_text,
                    model=self.model,
                    tool_calls=[],
                    raw_response=response,
                )

        except ToolError:
            raise
        except Exception as e:
            sanitized_msg = str(e)
            if self.api_key and self.api_key in sanitized_msg:
                sanitized_msg = sanitized_msg.replace(self.api_key, "[REDACTED_API_KEY]")
            logger.error("Error communicating with Gemini API: %s", sanitized_msg)
            raise GeminiExecutionError(f"Gemini API error: {sanitized_msg}") from None

    def continue_chat_with_tool_result(
        self,
        prompt: str,
        tool_name: str,
        tool_args: dict[str, Any],
        tool_result: dict[str, Any],
        history: Optional[List[ChatMessagePayload]] = None,
        system_instruction: Optional[str] = None,
        call_id: Optional[str] = None,
    ) -> GeminiChatResult:
        """
        Submits the result of an Android-executed tool back to Gemini so it can generate
        the final conversational response to the user.
        """
        if not self.is_configured:
            raise GeminiConfigurationError(
                "Gemini API key is not configured. Set GEMINI_API_KEY in backend/.env"
            )

        if not self._client:
            self._init_client()
            if not self._client:
                raise GeminiConfigurationError("Could not initialize Gemini SDK client.")

        sys_prompt = self._get_system_instruction(system_instruction)

        try:
            from google.genai import types

            config = types.GenerateContentConfig(
                system_instruction=sys_prompt,
                tools=[get_notes_gemini_tool()],
            )

            contents: list[types.Content] = []
            if history:
                for msg in history:
                    role = "user" if msg.role == "user" else "model"
                    contents.append(
                        types.Content(
                            role=role,
                            parts=[types.Part.from_text(text=msg.content)],
                        )
                    )

            # 1. User message with prompt
            contents.append(
                types.Content(
                    role="user",
                    parts=[types.Part.from_text(text=prompt)],
                )
            )

            # 2. Model turn that requested the function call (preserving thought_signature if available)
            cached_turn = self._call_cache.pop(call_id, None) if call_id else None
            if cached_turn is not None:
                contents.append(cached_turn)
            else:
                contents.append(
                    types.Content(
                        role="model",
                        parts=[
                            types.Part.from_function_call(
                                name=tool_name,
                                args=tool_args,
                            )
                        ],
                    )
                )

            # 3. User/Tool response with the execution result from Android
            contents.append(
                types.Content(
                    role="user",
                    parts=[
                        types.Part.from_function_response(
                            name=tool_name,
                            response=tool_result,
                        )
                    ],
                )
            )

            try:
                response = self._client.models.generate_content(
                    model=self.model,
                    contents=contents,
                    config=config,
                )
            except Exception as call_err:
                err_str = str(call_err)
                if self.model != FALLBACK_MODEL and ("504" in err_str or "DEADLINE_EXCEEDED" in err_str or "503" in err_str or "UNAVAILABLE" in err_str or "high demand" in err_str or "429" in err_str or "RESOURCE_EXHAUSTED" in err_str or "quota" in err_str.lower()):
                    logger.warning("Model '%s' had issue (%s). Falling back to '%s'", self.model, err_str[:80], FALLBACK_MODEL)
                    try:
                        response = self._client.models.generate_content(
                            model=FALLBACK_MODEL,
                            contents=contents,
                            config=config,
                        )
                    except Exception as fallback_err:
                        logger.warning("Fallback model also failed during tool follow-up: %s", str(fallback_err))
                        response = None
                else:
                    logger.warning("Gemini follow-up call failed (%s); providing graceful tool confirmation", err_str[:80])
                    response = None

            response_text = ""
            if response and response.text:
                response_text = response.text.strip()
            elif response and response.candidates:
                candidate = response.candidates[0]
                if candidate.content and candidate.content.parts:
                    part_texts = [
                        p.text for p in candidate.content.parts
                        if hasattr(p, "text") and p.text
                    ]
                    response_text = " ".join(part_texts).strip()

            if not response_text:
                title = tool_args.get("title", "")
                if "create" in tool_name:
                    response_text = f"I've created your note '{title}'." if title else "I've created your note."
                elif "delete" in tool_name:
                    response_text = "The note has been deleted."
                elif "update" in tool_name:
                    response_text = f"I've updated your note '{title}'." if title else "I've updated your note."
                else:
                    response_text = "Done! I've updated your notes."

            return GeminiChatResult(
                response_type="text_reply",
                text=response_text,
                model=self.model,
                tool_calls=[],
                raw_response=response,
            )

        except Exception as e:
            sanitized_msg = str(e)
            if self.api_key and self.api_key in sanitized_msg:
                sanitized_msg = sanitized_msg.replace(self.api_key, "[REDACTED_API_KEY]")
            logger.error("Error communicating with Gemini in tool-result continuation: %s", sanitized_msg)
            raise GeminiExecutionError(f"Gemini API error: {sanitized_msg}") from None
