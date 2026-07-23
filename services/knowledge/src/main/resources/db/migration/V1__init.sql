-- Knowledge base: articles and FAQs as DATA, audience-scoped, searchable.
-- Customers read customer articles from the shop's Support page, CSRs get
-- their own material on top, product owners get the how-to-build-products
-- library — one component, every channel. Tenant-owned (RLS in V2).
CREATE TABLE article (
    id           VARCHAR(64) PRIMARY KEY,
    href         VARCHAR(255),
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    title        VARCHAR(255) NOT NULL,
    -- markdown-ish body; channels render it as simple rich text
    body         VARCHAR(8000) NOT NULL,
    -- comma-separated search hints
    tags         VARCHAR(512),
    category     VARCHAR(128),
    -- who this article is FOR: customer | csr | sales | productOwner | all
    audience     VARCHAR(32) NOT NULL DEFAULT 'customer',
    -- published articles are live; drafts show only to authors
    status       VARCHAR(16) NOT NULL DEFAULT 'published',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_article_tenant ON article (tenant_id, audience, status);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Starter library for the demo tenant: the features shipped so far, told to
-- each audience. Operators replace or extend these from the console.
INSERT INTO article (id, href, tenant_id, title, body, tags, category, audience, status, created_at, last_update) VALUES
('faq-gift-data', '/tmf-api/knowledgeManagement/v4/article/faq-gift-data', 'genalpha',
 'How do I gift data to someone?',
 'Open My page and find the Mobile card. At the bottom you will see "Gift data". Pick a family member from the list, or type their phone number, choose whole GBs and press Send. Their meter grows the same instant, yours shrinks. You can gift what remains of your monthly data, up to half your plan per month. A gift never costs money — it is your data, your call.',
 'gift,data,share,family,GB,phone number', 'Mobile data', 'customer', 'published', now(), now()),
('faq-family-pays', '/tmf-api/knowledgeManagement/v4/article/faq-family-pays', 'genalpha',
 'How does family billing work?',
 'One person — the family payer — can pay for the others. You ask someone to pay for you from the Family page (they must accept), or a parent creates a child''s account directly. Everything the family funds lands on the payer''s single bill, attributed per person. What you buy with your own money stays on your own bill: paying for someone is not watching them.',
 'family,household,payer,bill,consent,admin', 'Family', 'customer', 'published', now(), now()),
('faq-ask-to-buy', '/tmf-api/knowledgeManagement/v4/article/faq-ask-to-buy', 'genalpha',
 'Why does my top-up say "sent for approval"?',
 'Your family set a monthly top-up allowance for you. Inside it, top-ups are instant and bill the family. Above it, your request goes to the family admins — you will get an inbox message the moment they approve or decline. Adults without an allowance simply pay from their own pocket; nobody is ever blocked from buying with their own money.',
 'topup,allowance,approval,ask to buy,family admin,held', 'Mobile data', 'customer', 'published', now(), now()),
('faq-rollover', '/tmf-api/knowledgeManagement/v4/article/faq-rollover', 'genalpha',
 'What happens to data I do not use?',
 'On plans with rollover, unused data moves into next month automatically — capped at one month''s allowance, and it lives one cycle. You will see an inbox note when it happens. Use it, or gift it to family.',
 'rollover,unused,data,next month,carry over', 'Mobile data', 'customer', 'published', now(), now()),
('faq-sim-puk', '/tmf-api/knowledgeManagement/v4/article/faq-sim-puk', 'genalpha',
 'Where do I find my PUK or reset my SIM PIN?',
 'On My page, every line shows its SIM. Press "Show PUK" to reveal the unlock code, or type a new PIN and press "Reset PIN" — it is pushed to your SIM over the air.',
 'sim,puk,pin,reset,locked,unlock', 'SIM & device', 'customer', 'published', now(), now()),
('csr-family-model', '/tmf-api/knowledgeManagement/v4/article/csr-family-model', 'genalpha',
 'CSR cheat-sheet: the household model',
 'Roles: OWNER (the payer), ADMIN (promoted by the owner — manages the family, orders bill the OWNER, cannot manage other admins), MEMBER (adult, own line only), CHILD (payer-created, purchases always family-funded or held for approval). Joining needs the member''s consent (pending until the payer accepts) except payer-created child accounts. Either side can leave. Adults'' self-paid products are NOT visible through the family view — only what the family funds. If a customer "cannot see" a family service, check: is the link active, is their role right, is the product payer-stamped?',
 'household,roles,admin,consent,troubleshoot,family', 'Family', 'csr', 'published', now(), now()),
('csr-gift-rollover-rules', '/tmf-api/knowledgeManagement/v4/article/csr-gift-rollover-rules', 'genalpha',
 'CSR cheat-sheet: gifting and rollover rules',
 'Gifting: whole GBs from the giver''s REMAINING data; the cap defaults to half the plan per month; a CHILD can never gift (their data is family-funded); scope defaults to household — the plan''s giftScope characteristic can widen it to the whole network. Unknown numbers bounce with a clear message. Rollover: unused GB rolls one cycle, capped at one month''s plan, unless the plan says rolloverEligible=false. All of it is product configuration — check the plan''s spec characteristics before assuming a defect.',
 'gift,rollover,cap,child,giftScope,troubleshoot', 'Mobile data', 'csr', 'published', now(), now()),
('csr-held-orders', '/tmf-api/knowledgeManagement/v4/article/csr-held-orders', 'genalpha',
 'CSR cheat-sheet: held (ask-to-buy) orders',
 'A held order is a family top-up above the member''s allowance, waiting for the payer or a family admin. It is NOT stuck: approve/decline lives on the family''s hub (Family page, "Waiting for your OK"). Approving releases it into normal fulfilment; declining cancels it and tells the requester. Only the payer and family admins can decide — CSRs should coach, not override.',
 'held,approval,topup,order,pending,ask to buy', 'Orders', 'csr', 'published', now(), now()),
('sales-family-pitch', '/tmf-api/knowledgeManagement/v4/article/sales-family-pitch', 'genalpha',
 'Selling the family proposition',
 'The hooks that land: ONE bill for the whole family with per-person attribution; parents create child accounts with their own login and safe defaults (every purchase needs an OK or an allowance); a co-parent can be family admin — same control, same view; data is shared generosity — gift GBs to family, or to any number on our network, in two taps; unused data rolls over. Differentiator: all of it is self-service, no store visit, and it works across B2C and B2B — a company can pay for an employee while their family setup stays intact.',
 'family,pitch,one bill,child,gift,selling points', 'Family', 'sales', 'published', now(), now()),
('po-create-product', '/tmf-api/knowledgeManagement/v4/article/po-create-product', 'genalpha',
 'How to create a product (spec, offering, price)',
 'Three TMF620 pieces, in order. 1) Product SPECIFICATION — what the thing IS: name plus characteristics (colour, size, giftScope…). 2) Product OFFERING — what you SELL: points at the spec, carries lifecycle status (Active = visible in channels) and category (Mobile plans, Devices, Top-ups…). 3) Product offering PRICE — one-time or recurring, linked from the offering. In the console: Catalog tabs, create spec, then offering, then price. Or tell the Copilot what you want ("a 5G plan with 25 GB for 19 euro") — it proposes all three parts and you review before anything is created. The catalog is the single source of truth: channels, ordering, billing and usage all read it live.',
 'product,create,spec,offering,price,catalog,how to', 'Catalog how-to', 'productOwner', 'published', now(), now()),
('po-bundles', '/tmf-api/knowledgeManagement/v4/article/po-bundles', 'genalpha',
 'Bundles and optional add-ons',
 'A bundle is an offering with isBundle=true and bundledProductOffering children. Fixed children ship always; choice groups (with min/max cardinality) render as options in the shop. A child keeps its own price unless a pricing rule discounts it inside the bundle. Customers see the hierarchy in the cart and on My page. After publishing, check the storefront: the bundle card shows the composed monthly total.',
 'bundle,add-on,children,cardinality,options,netflix', 'Catalog how-to', 'productOwner', 'published', now(), now()),
('po-rules-campaigns', '/tmf-api/knowledgeManagement/v4/article/po-rules-campaigns', 'genalpha',
 'Pricing rules and campaigns — data, not code',
 'Rules live in the console Rules tab and act immediately. ORDER rules block (e.g. max quantity); PRICING rules adjust (percent or amount) and show as labelled lines in cart and bill; GIFTING rules veto data gifts (e.g. size caps). Conditions are JSON-logic over the request context — offering ids, organizationId (B2B), member count, even a chosen colour (characteristicValues), so "10 percent off the blue one" is one rule. Deals surface automatically in the shop as teasers on related offerings. Disable the rule and the behaviour is gone — no redeploy, ever.',
 'rules,pricing,campaign,discount,jsonlogic,colour,deal', 'Catalog how-to', 'productOwner', 'published', now(), now()),
('po-gift-rollover-levers', '/tmf-api/knowledgeManagement/v4/article/po-gift-rollover-levers', 'genalpha',
 'Configuring gifting and rollover on a plan',
 'These are spec characteristics on the plan — no code, live within 30 seconds: giftable (false switches gifting off), giftScope (household = family only; tenant = any customer on your network), giftSharePerCycle (fraction of the plan giftable per month, default 0.5), rolloverEligible (false = nothing rolls), rolloverCapGB (default one month''s allowance). Add them to the product specification like any characteristic. Guardrails you cannot configure away: a child account never gifts, and gifts never cross tenants.',
 'gift,rollover,giftScope,characteristics,configure,network-wide', 'Catalog how-to', 'productOwner', 'published', now(), now()),
('po-copilot', '/tmf-api/knowledgeManagement/v4/article/po-copilot', 'genalpha',
 'Using the Product Copilot',
 'The Copilot (console, Copilot tab) turns a conversation into catalog objects. Describe the product; it asks what is missing, then proposes specs, offerings, prices and pricing rules as one reviewable package. Nothing is created until you confirm — and a failed apply rolls back everything it touched. If validation flags a problem, press "Ask the copilot to fix this". It follows the same modelling doctrine as this library: spec for what it is, offering for what you sell, rules for behaviour.',
 'copilot,assistant,create,proposal,ai,chat', 'Catalog how-to', 'productOwner', 'published', now(), now());
