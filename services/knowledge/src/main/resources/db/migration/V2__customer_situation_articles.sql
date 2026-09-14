-- The two customer articles GenAlpha Assist offers to SEND for a situation the
-- ontology reads on a customer: a known problem on their line, a paused line.
-- (An open bill already has "Understanding your bill".) Customer audience only —
-- the agent's reading and the operator's manual are never sent to a customer.
INSERT INTO article (id, href, tenant_id, title, body, tags, category, audience, status, created_at, last_update)
SELECT 'faq-outage-on-my-line', '/tmf-api/knowledgeManagement/v4/article/faq-outage-on-my-line', 'genalpha',
       'When there is a problem on your line',
       'If we know about a problem on your line, we say so on your Home page and in the line check — you do not need to report it. Our network team is already working on it, and your connection comes back on its own when it is fixed. You can keep using mobile data in the meantime; if your plan includes it, hotspot from your phone. If the problem is only at your address, restart your router (unplug it for ten seconds) and run the line check again. We do not charge for the time a known outage keeps you offline.',
       'outage,incident,no internet,slow,network', 'Network', 'customer', 'published', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article WHERE id = 'faq-outage-on-my-line');

INSERT INTO article (id, href, tenant_id, title, body, tags, category, audience, status, created_at, last_update)
SELECT 'faq-line-paused', '/tmf-api/knowledgeManagement/v4/article/faq-line-paused', 'genalpha',
       'Your line is paused — what that means',
       'A paused line keeps its number and SIM, but nothing connects and nothing is charged until you resume it. You can pause for a holiday and resume any time from your Home page or Services — it takes effect within a minute. A line paused for more than 90 days is resumed automatically so the number is not lost.',
       'paused,pause,suspended,resume', 'Mobile', 'customer', 'published', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article WHERE id = 'faq-line-paused');
