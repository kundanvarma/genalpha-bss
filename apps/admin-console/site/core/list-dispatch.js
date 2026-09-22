/* The custom panes: a resource flagged as a desk renders its own panel instead of the table. Called first by loadList; true = handled. */
'use strict';

function renderCustomPane() {
  if (active.home) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    document.querySelector('.table-wrap')?.setAttribute('hidden', '');
    // home.js loads after this file; a fast sign-in can reach here before it has run
    if (typeof renderHome === 'function') renderHome(); else window.addEventListener('load', () => renderHome(), { once: true });
    return true;
  }
  if (active.copilot) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderProductCopilot();
    return true;
  }
  if (active.approvals || active.envelopes) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    if (active.approvals) renderApprovalsDesk(); else renderEnvelopes();
    return true;
  }
  if (active.growthCopilot) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderGrowthCopilot();
    return true;
  }
  if (active.audienceBuilder) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderAudienceBuilder();
    return true;
  }
  if (active.pipelineBoard) {
    el('editor').hidden = true;
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderPipelineBoard();
    return true;
  }
  if (active.socialListening) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderSocialListening();
    return true;
  }
  if (active.socialCare) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderSocialCare();
    return true;
  }
  if (active.attribution) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderAttribution();
    return true;
  }
  if (active.voc) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderVoc();
    return true;
  }
  if (active.aiflows) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderAiFlows();
    return true;
  }
  if (active.decisions || active.learningContracts) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    if (active.decisions) renderDecisions(); else renderLearningContracts();
    return true;
  }
  if (active.deviceEntitlements) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderDeviceEntitlements();
    return true;
  }
  if (active.ontology) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderOntology();
    return true;
  }
  if (active.staff) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderStaff();
    return true;
  }
  if (active.workforce) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderWorkforce();
    return true;
  }
  if (active.deskLearning) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderDeskLearning();
    return true;
  }
  if (active.reporting || active.integrations) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    if (active.reporting) renderReporting(); else renderIntegrations();
    return true;
  }
  if (active.wholesaleOwners || active.accessProduct || active.wholesaleSettlement
      || active.mobileWholesale || active.mobileWholesaleProvider) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    if (active.wholesaleOwners) renderWholesaleOwners();
    else if (active.accessProduct) renderAccessProduct();
    else if (active.mobileWholesale) renderMobileWholesale();
    else if (active.mobileWholesaleProvider) renderMobileWholesaleProvider();
    else renderWholesaleSettlement();
    return true;
  }
  return false;
}
