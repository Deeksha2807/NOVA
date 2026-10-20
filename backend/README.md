# NOVA Backend Service

FastAPI backend providing Google Gemini AI intelligence and function/tool-calling orchestration for the **NOVA** Personal AI Agent Android application.

---

## Architecture & Security Model

The backend acts as a secure intermediary between the NOVA Android client and Google's Gemini models:

1. **API Key Security:** The `GEMINI_API_KEY` is loaded on the backend from `.env` and is **never** sent to the Android device, logged, or exposed in error responses.
2. **Local Data Isolation:** The backend **never** connects to Android SQLite/Room databases directly. It only receives user prompts and returns structured instructions for the Android app to execute locally.
3. **Untrusted Model Output Validation:** All Gemini-generated function calls are treated as untrusted inputs. Tool names and argument types are strictly validated via Pydantic schemas before being returned to Android. Unknown tools or malformed arguments are rejected.
4. **Client-Side Confirmation for Destructive Actions:** Actions like `delete_note` are flagged with `requires_confirmation: true`, requiring the Android client to prompt the user before execution.

---

## The 5 Local Android Note Tools

The backend declares and validates five local Android note tools matching `com.nova.personalagent.data.NotesRepository`:

| Tool Name | Parameters | Description | Destructive / Confirmed |
| :--- | :--- | :--- | :--- |
| `create_note` | `title` (str, req), `content` (str, req) | Creates and stores a new note in local Room DB | No (`requires_confirmation: false`) |
| `search_notes` | `query` (str, req) | Searches notes by matching text in title or content | No (`requires_confirmation: false`) |
| `get_note` | `id` (int, req) | Retrieves a specific note by ID | No (`requires_confirmation: false`) |
| `update_note` | `id` (int, req), `title` (str, req), `content` (str, req) | Updates an existing note's title and content | No (`requires_confirmation: false`) |
| `delete_note` | `id` (int, req) | Deletes a note permanently from Room | **Yes (`requires_confirmation: true`)** |

---

## API Protocol Specification

### 1. `POST /api/chat`
Sends user prompt (and optional prior chat history) to Gemini.

#### A. Case 1: Conversational Reply (No tool execution needed)
**Request:**
```json
{
  "prompt": "Hello NOVA, what can you do?",
  "history": []
}
```

**Response (200 OK):**
```json
{
  "response_type": "text_reply",
  "reply": "Hello! I am NOVA, your personal agent. I can help you create, search, view, update, and manage your notes.",
  "model": "gemini-2.5-flash",
  "tool_calls": [],
  "status": "success"
}
```

#### B. Case 2: Tool Execution Request (Android executes locally)
**Request:**
```json
{
  "prompt": "Create a note titled Grocery List with milk and bread",
  "history": []
}
```

**Response (200 OK):**
```json
{
  "response_type": "tool_request",
  "reply": "I'll create that note for you.",
  "model": "gemini-2.5-flash",
  "tool_calls": [
    {
      "call_id": "call_9f8a7b6c5d4e",
      "tool_name": "create_note",
      "arguments": {
        "title": "Grocery List",
        "content": "milk and bread"
      },
      "requires_confirmation": false
    }
  ],
  "status": "success"
}
```

#### C. Case 3: Destructive Tool Request (Requires Android user confirmation)
**Request:**
```json
{
  "prompt": "Delete note number 5",
  "history": []
}
```

**Response (200 OK):**
```json
{
  "response_type": "tool_request",
  "reply": "Are you sure you want to delete note 5?",
  "model": "gemini-2.5-flash",
  "tool_calls": [
    {
      "call_id": "call_1a2b3c4d5e6f",
      "tool_name": "delete_note",
      "arguments": {
        "id": 5
      },
      "requires_confirmation": true
    }
  ],
  "status": "success"
}
```

---

### 2. `POST /api/chat/tool-result`
Follow-up endpoint used after Android executes the local tool in Room. Gemini incorporates the execution result and generates the final spoken/displayed response.

**Request:**
```json
{
  "prompt": "Create a note titled Grocery List with milk and bread",
  "tool_call": {
    "call_id": "call_9f8a7b6c5d4e",
    "tool_name": "create_note",
    "arguments": {
      "title": "Grocery List",
      "content": "milk and bread"
    }
  },
  "tool_result": {
    "success": true,
    "note_id": 12,
    "message": "Note inserted successfully"
  },
  "history": []
}
```

**Response (200 OK):**
```json
{
  "response_type": "text_reply",
  "reply": "I've saved your 'Grocery List' note.",
  "model": "gemini-2.5-flash",
  "tool_calls": [],
  "status": "success"
}
```

#### Client-Side Cancellation Flow:
If the user cancels a confirmation dialog on Android (e.g., clicks "Cancel" on delete), Android sends:
```json
{
  "prompt": "Delete note number 5",
  "tool_call": {
    "call_id": "call_1a2b3c4d5e6f",
    "tool_name": "delete_note",
    "arguments": { "id": 5 }
  },
  "tool_result": {
    "success": false,
    "cancelled": true,
    "message": "User declined deletion confirmation"
  },
  "history": []
}
```
Gemini responds with a natural cancellation acknowledgement:
```json
{
  "response_type": "text_reply",
  "reply": "Understood. Note 5 was not deleted.",
  "model": "gemini-2.5-flash",
  "tool_calls": [],
  "status": "success"
}
```

---

## Testing

Run all unit and integration tests with `pytest`:
```bash
# In backend virtual environment:
pytest test_main.py -v
```
All 17 tests validate health checks, request validation, tool declaration schemas, untrusted input rejection, confirmation flags, tool-result continuations, and secret shielding.
