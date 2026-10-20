"""
Automated unit and integration tests for NOVA Backend.
Tests health check, request validation, Gemini tool-call protocol,
untrusted model output validation, tool-result round-trips, and error shielding.
"""

from unittest.mock import MagicMock, PropertyMock, patch
import pytest
from fastapi.testclient import TestClient

from gemini_service import (
    GeminiChatResult,
    GeminiConfigurationError,
    GeminiExecutionError,
    GeminiService,
)
from main import app, gemini_service
from tools import (
    ToolArgumentValidationError,
    ToolCallRequest,
    UnknownToolError,
    validate_tool_call,
)

client = TestClient(app)


# ── Health Endpoint Tests ────────────────────────────────────────────────────

def test_health_endpoint_when_unconfigured():
    """Verify /health returns 'degraded' when no Gemini key is set."""
    with patch.object(GeminiService, "is_configured", new_callable=PropertyMock, return_value=False):
        response = client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "degraded"
        assert data["gemini_configured"] is False
        assert "model" in data
        assert data["version"] == "1.0.0"


def test_health_endpoint_when_configured():
    """Verify /health returns 'ok' when Gemini key is configured."""
    with patch.object(GeminiService, "is_configured", new_callable=PropertyMock, return_value=True):
        response = client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert data["gemini_configured"] is True


# ── Request Validation Tests ─────────────────────────────────────────────────

def test_chat_empty_prompt_validation():
    """Verify empty prompt returns HTTP 422 error."""
    response = client.post("/api/chat", json={"prompt": ""})
    assert response.status_code == 422


def test_chat_whitespace_prompt_validation():
    """Verify whitespace-only prompt returns HTTP 422 error."""
    response = client.post("/api/chat", json={"prompt": "    "})
    assert response.status_code == 422


def test_chat_invalid_json_body():
    """Verify missing required prompt field returns HTTP 422."""
    response = client.post("/api/chat", json={"not_a_prompt": "hello"})
    assert response.status_code == 422


# ── Unconfigured / Service Unavailable Tests ─────────────────────────────────

def test_chat_when_gemini_not_configured():
    """Verify chat returns HTTP 503 when API key is missing."""
    with patch.object(
        gemini_service,
        "generate_chat_response",
        side_effect=GeminiConfigurationError("Not configured"),
    ):
        response = client.post("/api/chat", json={"prompt": "Hello"})
        assert response.status_code == 503
        data = response.json()
        assert "not configured" in data["detail"].lower()


# ── Direct Text Response (Normal Reply) ──────────────────────────────────────

def test_chat_normal_text_reply():
    """Verify conversational text response when no tools are requested."""
    mock_result = GeminiChatResult(
        response_type="text_reply",
        text="Hello! I am NOVA, your personal agent.",
        model="gemini-2.5-flash",
        tool_calls=[],
    )

    with patch.object(
        gemini_service,
        "generate_chat_response",
        return_value=mock_result,
    ):
        response = client.post("/api/chat", json={"prompt": "Hello"})
        assert response.status_code == 200
        data = response.json()
        assert data["response_type"] == "text_reply"
        assert data["reply"] == "Hello! I am NOVA, your personal agent."
        assert data["tool_calls"] == []
        assert data["status"] == "success"


# ── Tool-Calling Protocol Tests ──────────────────────────────────────────────

def test_chat_create_note_tool_request():
    """Verify that Gemini function call for create_note is formatted and validated."""
    validated_tool = ToolCallRequest(
        call_id="call_test_123",
        tool_name="create_note",
        arguments={"title": "Shopping List", "content": "Milk, Eggs, Bread"},
        requires_confirmation=False,
    )
    mock_result = GeminiChatResult(
        response_type="tool_request",
        text="I will create that note for you.",
        model="gemini-2.5-flash",
        tool_calls=[validated_tool],
    )

    with patch.object(
        gemini_service,
        "generate_chat_response",
        return_value=mock_result,
    ):
        response = client.post("/api/chat", json={"prompt": "Take a note for shopping"})
        assert response.status_code == 200
        data = response.json()
        assert data["response_type"] == "tool_request"
        assert len(data["tool_calls"]) == 1
        tc = data["tool_calls"][0]
        assert tc["call_id"] == "call_test_123"
        assert tc["tool_name"] == "create_note"
        assert tc["arguments"]["title"] == "Shopping List"
        assert tc["requires_confirmation"] is False


def test_chat_delete_note_requires_confirmation():
    """Verify that delete_note tool request is flagged with requires_confirmation=True."""
    validated_tool = ToolCallRequest(
        call_id="call_del_999",
        tool_name="delete_note",
        arguments={"id": 42},
        requires_confirmation=True,
    )
    mock_result = GeminiChatResult(
        response_type="tool_request",
        text="Are you sure you want to delete note 42?",
        model="gemini-2.5-flash",
        tool_calls=[validated_tool],
    )

    with patch.object(
        gemini_service,
        "generate_chat_response",
        return_value=mock_result,
    ):
        response = client.post("/api/chat", json={"prompt": "Delete note 42"})
        assert response.status_code == 200
        data = response.json()
        assert data["response_type"] == "tool_request"
        tc = data["tool_calls"][0]
        assert tc["tool_name"] == "delete_note"
        assert tc["arguments"]["id"] == 42
        assert tc["requires_confirmation"] is True


def test_chat_search_and_update_tools_validation():
    """Verify local schema validation for search_notes and update_note."""
    # search_notes validation
    search_call = validate_tool_call("search_notes", {"query": "groceries"})
    assert search_call.tool_name == "search_notes"
    assert search_call.arguments["query"] == "groceries"
    assert search_call.requires_confirmation is False

    # update_note validation
    update_call = validate_tool_call("update_note", {"id": 10, "title": "New Title", "content": "New Body"})
    assert update_call.tool_name == "update_note"
    assert update_call.arguments["id"] == 10
    assert update_call.requires_confirmation is False

    # get_note validation
    get_call = validate_tool_call("get_note", {"id": 5})
    assert get_call.tool_name == "get_note"
    assert get_call.arguments["id"] == 5


def test_chat_rejects_unknown_tools():
    """Verify that unknown tool names from untrusted model output are rejected."""
    with pytest.raises(UnknownToolError):
        validate_tool_call("delete_all_files", {})

    with pytest.raises(UnknownToolError):
        validate_tool_call("send_email", {"to": "someone@example.com"})


def test_chat_rejects_malformed_tool_arguments():
    """Verify that invalid arguments (e.g. non-numeric ID) raise validation errors."""
    with pytest.raises(ToolArgumentValidationError):
        # id must be an integer, not string or negative
        validate_tool_call("get_note", {"id": "not_an_id"})

    with pytest.raises(ToolArgumentValidationError):
        # id must be >= 1
        validate_tool_call("delete_note", {"id": 0})

    with pytest.raises(ToolArgumentValidationError):
        # title is required for create_note
        validate_tool_call("create_note", {"content": "Content only"})


def test_chat_endpoint_shields_invalid_tool_calls():
    """Verify /api/chat returns HTTP 502 with safe message if Gemini generates invalid tool."""
    with patch.object(
        gemini_service,
        "generate_chat_response",
        side_effect=ToolArgumentValidationError("Invalid args"),
    ):
        response = client.post("/api/chat", json={"prompt": "Do something"})
        assert response.status_code == 502
        data = response.json()
        assert "invalid tool request" in data["detail"].lower()


# ── Tool-Result Follow-Up Endpoint Tests ─────────────────────────────────────

def test_tool_result_follow_up_success():
    """Verify POST /api/chat/tool-result incorporates execution output and returns final reply."""
    mock_final_result = GeminiChatResult(
        response_type="text_reply",
        text="I've created your note 'Shopping List' with ID 1.",
        model="gemini-2.5-flash",
        tool_calls=[],
    )

    with patch.object(
        gemini_service,
        "continue_chat_with_tool_result",
        return_value=mock_final_result,
    ) as mock_continue:
        payload = {
            "prompt": "Create note Shopping List with milk and eggs",
            "tool_call": {
                "call_id": "call_123",
                "tool_name": "create_note",
                "arguments": {"title": "Shopping List", "content": "milk and eggs"},
            },
            "tool_result": {
                "success": True,
                "note_id": 1,
            },
            "history": [],
        }

        response = client.post("/api/chat/tool-result", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert data["response_type"] == "text_reply"
        assert "Shopping List" in data["reply"]
        assert data["tool_calls"] == []

        mock_continue.assert_called_once()
        call_kwargs = mock_continue.call_args.kwargs
        assert call_kwargs["tool_name"] == "create_note"
        assert call_kwargs["tool_result"]["note_id"] == 1


def test_tool_result_follow_up_cancellation():
    """Verify client cancellation (e.g. user declined delete confirmation) flows through."""
    mock_final_result = GeminiChatResult(
        response_type="text_reply",
        text="Understood. The note was not deleted.",
        model="gemini-2.5-flash",
        tool_calls=[],
    )

    with patch.object(
        gemini_service,
        "continue_chat_with_tool_result",
        return_value=mock_final_result,
    ):
        payload = {
            "prompt": "Delete note 42",
            "tool_call": {
                "call_id": "call_del_42",
                "tool_name": "delete_note",
                "arguments": {"id": 42},
            },
            "tool_result": {
                "success": False,
                "cancelled": True,
                "message": "User declined confirmation",
            },
        }

        response = client.post("/api/chat/tool-result", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert data["response_type"] == "text_reply"
        assert "not deleted" in data["reply"]


# ── Error Shielding & Security Tests ─────────────────────────────────────────

def test_chat_execution_error_shielding():
    """Verify GeminiExecutionError returns HTTP 502 with safe generic message."""
    with patch.object(
        gemini_service,
        "generate_chat_response",
        side_effect=GeminiExecutionError("Gemini API error: quota exceeded"),
    ):
        response = client.post("/api/chat", json={"prompt": "What is the weather?"})
        assert response.status_code == 502
        data = response.json()
        assert "failed to generate response" in data["detail"].lower()


def test_api_key_redaction_in_service():
    """Verify that if an error message contains the API key, it is redacted."""
    service = GeminiService(api_key="SECRET_API_KEY_12345")
    assert service.is_configured is True

    fake_client = MagicMock()
    fake_client.models.generate_content.side_effect = RuntimeError(
        "Failed connection using SECRET_API_KEY_12345 to endpoint"
    )
    service._client = fake_client

    with pytest.raises(GeminiExecutionError) as exc_info:
        service.generate_chat_response(prompt="Test prompt")

    error_str = str(exc_info.value)
    assert "SECRET_API_KEY_12345" not in error_str
    assert "[REDACTED_API_KEY]" in error_str
