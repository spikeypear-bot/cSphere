import '@testing-library/jest-dom/vitest'

// jsdom has no modal <dialog>; behave like a browser: showModal opens it, and
// close() closes it and fires "close" (which Escape also ends in).
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}
