# genalpha-bss MCP server

The **agentic connective tissue** of the AI-slice PoC. It exposes the BSS
lead-to-order loop as [Model Context Protocol](https://modelcontextprotocol.io)
tools, so any MCP-speaking AI agent — Claude as a B2B sales agent, for
instance — can drive the whole workflow over the TM Forum Open APIs:

| MCP tool | TMF API it calls | What the agent does |
|---|---|---|
| `draft_intent` | AI seam (`/ai/v1/intentDraft`) | plain-language ask → structured TMF921 intent |
| `submit_intent` | TMF921 Intent | run autonomous feasibility + get the network's proposal |
| `create_quote` | TMF648 Quote | price the proposal, token economics on the line items |
| `accept_quote` | TMF648 → TMF622 | approve + accept → places the order → provisions the slice |
| `list_quotes` | TMF648 Quote | review the pipeline |

This is the same standardised agent-to-agent pattern (MCP / A2A) the industry
is converging on — the BSS is just another set of tools an agent can hold,
authenticated as a tenant-scoped machine identity like every other
service-to-service caller.

## Use with Claude Desktop / Claude Code

```jsonc
// claude_desktop_config.json (or a Claude Code MCP config)
{
  "mcpServers": {
    "genalpha-bss": {
      "command": "node",
      "args": ["/absolute/path/to/integrations/mcp-server/server.js"],
      "env": {
        "BSS_GATEWAY_URL": "http://localhost:8080",
        "BSS_CLIENT_ID": "bss-demo",
        "BSS_USERNAME": "demo",
        "BSS_PASSWORD": "demo"
      }
    }
  }
}
```

Then ask Claude: *"A stadium wants a 5G slice with AI glasses for their
tournament — draft the intent, check feasibility, quote it and place the
order."* Claude will chain the five tools and close the commercial loop.

Install: `cd integrations/mcp-server && npm install`.
