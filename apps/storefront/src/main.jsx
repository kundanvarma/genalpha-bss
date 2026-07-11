import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import './styles.css';

// White-labeling is more than a logo: the host tenant's brand color themes
// the whole channel (genalpha's teal is the stylesheet default; nova goes purple).
const brand = window.BSS_STOREFRONT_CONFIG || {};
if (brand.brandColor) {
  document.documentElement.style.setProperty('--teal', brand.brandColor);
  document.documentElement.style.setProperty('--teal-soft', brand.brandColor + '1F');
}
if (brand.brandName) document.title = `${brand.brandName} · shop`;

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter basename="/shop">
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
