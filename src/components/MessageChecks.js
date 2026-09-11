import React from 'react';

// Галочки доставки/прочтения — как в Android-клиенте (ic_delivered / ic_read):
// зелёные #34D399, 18px, вторая накладывается на первую со сдвигом -12px.
const CheckIcon = ({ overlap = false }) => (
  <svg
    width="18"
    height="18"
    viewBox="0 0 24 24"
    aria-hidden="true"
    style={{ display: 'block', marginLeft: overlap ? -12 : 0, flexShrink: 0 }}
  >
    <path fill="#34D399" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
  </svg>
);

const MessageChecks = ({ delivered, read }) => {
  if (!delivered && !read) return null;
  return (
    <span
      className="message-checks"
      title={read ? 'Read' : 'Delivered'}
      style={{ display: 'inline-flex', alignItems: 'center', height: 18 }}
    >
      <CheckIcon />
      {read && <CheckIcon overlap />}
    </span>
  );
};

export default MessageChecks;
