"""
NOVA Backend - Tool Calling Definitions & Validation
Defines Google Gemini function declarations for NOVA's 5 local Android note tools.
Provides strict validation of untrusted model outputs before passing tool requests to the client.
"""

from __future__ import annotations

import uuid
from typing import Any, Callable, Dict, Optional
from pydantic import BaseModel, Field, ValidationError


# ── Exceptions ───────────────────────────────────────────────────────────────

class ToolError(Exception):
    """Base exception for tool-calling validation issues."""
    pass


class UnknownToolError(ToolError):
    """Raised when Gemini requests a tool name not in the registry."""
    pass


class ToolArgumentValidationError(ToolError):
    """Raised when tool arguments fail Pydantic schema validation."""
    pass


# ── Pydantic Argument Schemas ────────────────────────────────────────────────

class CreateNoteArgs(BaseModel):
    title: str = Field(..., min_length=1, max_length=500, description="Title of the note")
    content: str = Field(..., max_length=10000, description="Body content of the note")


class SearchNotesArgs(BaseModel):
    query: str = Field(..., min_length=1, max_length=500, description="Search keyword or query")


class GetNoteArgs(BaseModel):
    id: int = Field(..., ge=1, description="Unique integer ID of the note")


class UpdateNoteArgs(BaseModel):
    id: int = Field(..., ge=1, description="Unique integer ID of the note to update")
    title: str = Field(..., min_length=1, max_length=500, description="Updated title of the note")
    content: str = Field(..., max_length=10000, description="Updated body content of the note")


class DeleteNoteArgs(BaseModel):
    id: int = Field(..., ge=1, description="Unique integer ID of the note to delete")


# ── Validated Tool Call Request Model ────────────────────────────────────────

class ToolCallRequest(BaseModel):
    """
    Structured request instructing the Android client to execute a local tool.
    Never executes on the backend; only Android Room can execute note tools.
    """
    call_id: str = Field(..., description="Unique ID for this tool invocation")
    tool_name: str = Field(..., description="Name of the approved tool")
    arguments: Dict[str, Any] = Field(..., description="Validated JSON arguments")
    requires_confirmation: bool = Field(
        default=False,
        description="Whether this tool is destructive and requires user confirmation on device",
    )


# ── Tool Registry ────────────────────────────────────────────────────────────

TOOL_ARGUMENT_SCHEMAS: Dict[str, type[BaseModel]] = {
    "create_note": CreateNoteArgs,
    "search_notes": SearchNotesArgs,
    "get_note": GetNoteArgs,
    "update_note": UpdateNoteArgs,
    "delete_note": DeleteNoteArgs,
}

# Tools that mutate or delete user data destructively
DESTRUCTIVE_TOOLS: set[str] = {
    "delete_note",
}


def validate_tool_call(
    tool_name: str,
    raw_args: Dict[str, Any],
    call_id: Optional[str] = None,
) -> ToolCallRequest:
    """
    Validates Gemini-generated tool call name and arguments as untrusted input.
    Rejects unknown tools, verifies argument types, and marks destructive tools
    with `requires_confirmation = True`.
    """
    if tool_name not in TOOL_ARGUMENT_SCHEMAS:
        raise UnknownToolError(f"Unknown or unsupported tool requested: '{tool_name}'")

    schema_cls = TOOL_ARGUMENT_SCHEMAS[tool_name]
    try:
        validated_obj = schema_cls.model_validate(raw_args)
    except ValidationError as e:
        raise ToolArgumentValidationError(
            f"Invalid arguments for tool '{tool_name}': {e.errors()}"
        ) from e

    cid = call_id or f"call_{uuid.uuid4().hex[:12]}"
    is_destructive = tool_name in DESTRUCTIVE_TOOLS

    return ToolCallRequest(
        call_id=cid,
        tool_name=tool_name,
        arguments=validated_obj.model_dump(),
        requires_confirmation=is_destructive,
    )


# ── Gemini Function Declarations (google-genai SDK) ──────────────────────────

def get_notes_gemini_tool() -> Any:
    """
    Constructs the `types.Tool` instance containing FunctionDeclarations
    for the five local Android note tools for the `google-genai` SDK.
    """
    from google.genai import types

    declarations = [
        types.FunctionDeclaration(
            name="create_note",
            description="Creates and stores a new note with a title and content in the user's local notes database.",
            parameters=types.Schema(
                type=types.Type.OBJECT,
                properties={
                    "title": types.Schema(
                        type=types.Type.STRING,
                        description="The title or headline of the note.",
                    ),
                    "content": types.Schema(
                        type=types.Type.STRING,
                        description="The detailed text body or content of the note.",
                    ),
                },
                required=["title", "content"],
            ),
        ),
        types.FunctionDeclaration(
            name="search_notes",
            description="Searches existing notes in the local database matching keywords in title or content.",
            parameters=types.Schema(
                type=types.Type.OBJECT,
                properties={
                    "query": types.Schema(
                        type=types.Type.STRING,
                        description="The search keyword or phrase to search for.",
                    ),
                },
                required=["query"],
            ),
        ),
        types.FunctionDeclaration(
            name="get_note",
            description="Retrieves a single note by its unique numeric ID from the local database.",
            parameters=types.Schema(
                type=types.Type.OBJECT,
                properties={
                    "id": types.Schema(
                        type=types.Type.INTEGER,
                        description="The unique integer ID of the note to retrieve.",
                    ),
                },
                required=["id"],
            ),
        ),
        types.FunctionDeclaration(
            name="update_note",
            description="Updates an existing note's title and content in the local database by ID.",
            parameters=types.Schema(
                type=types.Type.OBJECT,
                properties={
                    "id": types.Schema(
                        type=types.Type.INTEGER,
                        description="The unique integer ID of the note to update.",
                    ),
                    "title": types.Schema(
                        type=types.Type.STRING,
                        description="The updated title for the note.",
                    ),
                    "content": types.Schema(
                        type=types.Type.STRING,
                        description="The updated content for the note.",
                    ),
                },
                required=["id", "title", "content"],
            ),
        ),
        types.FunctionDeclaration(
            name="delete_note",
            description="Deletes a note permanently by its unique numeric ID from the local database. Destructive action.",
            parameters=types.Schema(
                type=types.Type.OBJECT,
                properties={
                    "id": types.Schema(
                        type=types.Type.INTEGER,
                        description="The unique integer ID of the note to delete.",
                    ),
                },
                required=["id"],
            ),
        ),
    ]

    return types.Tool(function_declarations=declarations)
