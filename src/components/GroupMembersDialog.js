import React, { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import * as api from '../api/chatMembers';
import '../styles/GroupMembersDialog.css';

/**
 * Участники группы: кто состоит, с какой ролью, и что я могу с этим сделать.
 *
 * Набор действий зависит от моей роли, а не от типа чата: владелец умеет всё,
 * что админ, плюс роли, передача владения и переключение публичности. Сервер
 * проверяет то же самое — здесь мы только не показываем заведомо запретное.
 */
const GroupMembersDialog = ({ chat, currentUserId, allUsers = [], onClose, onChanged }) => {
  const [members, setMembers] = useState([]);
  const [invites, setInvites] = useState([]);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState('');
  const [isPublic, setIsPublic] = useState(!(chat.isPublic === false));

  const myRole = members.find((m) => String(m.userId) === String(currentUserId))?.role;
  const isOwner = myRole === 'OWNER';
  const canManage = isOwner || myRole === 'ADMIN'
    // Чат из прежних времён: участников нет ни у кого, ограничивать некого
    || members.length === 0;

  const load = useCallback(async () => {
    try {
      const [loadedMembers, loadedInvites] = await Promise.all([
        api.getMembers(chat.id),
        api.getInvites(chat.id).catch(() => []), // приглашения видны только управляющим
      ]);
      setMembers(loadedMembers || []);
      setInvites(loadedInvites || []);
    } catch (error) {
      console.error('Error loading members:', error);
      toast.error('Could not load the member list');
    } finally {
      setLoading(false);
    }
  }, [chat.id]);

  useEffect(() => { load(); }, [load]);

  const act = async (action, successText) => {
    try {
      await action();
      if (successText) toast.success(successText);
      await load();
      if (onChanged) onChanged();
    } catch (error) {
      console.error('Group action failed:', error);
      toast.error(error.message || 'The server refused this action');
    }
  };

  const copyLink = async (token) => {
    const link = api.inviteLink(token);
    try {
      await navigator.clipboard.writeText(link);
      toast.success('Invite link copied');
    } catch {
      // Буфер обмена доступен не везде (http, старые браузеры) — покажем ссылку
      window.prompt('Copy the invite link:', link);
    }
  };

  const notMembers = allUsers.filter(
    (user) => !members.some((m) => String(m.userId) === String(user.id))
  );

  return (
    <div className="group-members-overlay" onClick={onClose}>
      <div className="group-members-dialog" onClick={(e) => e.stopPropagation()}>
        <h3>{chat.name}</h3>

        {loading ? (
          <p className="group-members-note">Loading…</p>
        ) : (
          <>
            <ul className="group-members-list">
              {members.map((member) => (
                <li key={member.userId} className="group-members-row">
                  <span className="group-members-name">
                    {member.username || `User ${member.userId}`}
                    {String(member.userId) === String(currentUserId) ? ' (you)' : ''}
                  </span>
                  <span className={`group-members-role role-${member.role.toLowerCase()}`}>
                    {member.role === 'OWNER' ? 'owner' : member.role === 'ADMIN' ? 'admin' : 'member'}
                  </span>
                  <span className="group-members-actions">
                    {isOwner && member.role === 'MEMBER' && (
                      <button onClick={() => act(
                        () => api.setMemberRole(chat.id, member.userId, 'ADMIN'), 'Now an admin'
                      )}>Make admin</button>
                    )}
                    {isOwner && member.role === 'ADMIN' && (
                      <button onClick={() => act(
                        () => api.setMemberRole(chat.id, member.userId, 'MEMBER'), 'No longer an admin'
                      )}>Remove admin</button>
                    )}
                    {isOwner && member.role !== 'OWNER' && (
                      <button onClick={() => {
                        if (!window.confirm(`Hand the group over to ${member.username}? You stay as an admin.`)) return;
                        act(() => api.transferOwnership(chat.id, member.userId), 'Ownership transferred');
                      }}>Make owner</button>
                    )}
                    {canManage && member.role !== 'OWNER'
                      && String(member.userId) !== String(currentUserId) && (
                      <button className="danger" onClick={() => act(
                        () => api.removeMember(chat.id, member.userId), 'Removed from the group'
                      )}>Remove</button>
                    )}
                  </span>
                </li>
              ))}
              {members.length === 0 && (
                <li className="group-members-note">
                  This group was created before members existed — anyone can see it.
                </li>
              )}
            </ul>

            {canManage && (
              <div className="group-members-add">
                <select value={adding} onChange={(e) => setAdding(e.target.value)}>
                  <option value="">Add someone…</option>
                  {notMembers.map((user) => (
                    <option key={user.id} value={user.id}>{user.username}</option>
                  ))}
                </select>
                <button
                  disabled={!adding}
                  onClick={() => act(() => api.addMember(chat.id, adding), 'Added to the group')
                    .then(() => setAdding(''))}
                >
                  Add
                </button>
              </div>
            )}

            {canManage && (
              <div className="group-members-invites">
                <div className="group-members-invites-header">
                  <span>Invite links</span>
                  <button onClick={() => act(
                    () => api.createInvite(chat.id, 168, 0), 'Invite link created'
                  )}>New link (7 days)</button>
                </div>
                {invites.length === 0 && <p className="group-members-note">No links yet.</p>}
                {invites.map((invite) => (
                  <div key={invite.token} className="group-members-invite">
                    <code>{invite.token.slice(0, 10)}…</code>
                    <span className="group-members-note">
                      {invite.usable ? `used ${invite.uses}` : 'not valid'}
                    </span>
                    <button onClick={() => copyLink(invite.token)}>Copy</button>
                    {invite.usable && (
                      <button className="danger" onClick={() => act(
                        () => api.revokeInvite(chat.id, invite.token), 'Link revoked'
                      )}>Revoke</button>
                    )}
                  </div>
                ))}
              </div>
            )}

            {isOwner && (
              <label className="group-members-public">
                <input
                  type="checkbox"
                  checked={isPublic}
                  onChange={(e) => {
                    const value = e.target.checked;
                    setIsPublic(value);
                    act(() => api.updateChat(chat.id, { isPublic: value }),
                      value ? 'The group is now public' : 'The group is now private');
                  }}
                />
                Public group — anyone can find and read it
              </label>
            )}

            <div className="group-members-footer">
              {members.some((m) => String(m.userId) === String(currentUserId)) && (
                <button className="danger" onClick={() => {
                  if (!window.confirm('Leave this group?')) return;
                  act(() => api.leaveChat(chat.id), 'You left the group').then(onClose);
                }}>Leave group</button>
              )}
              <button onClick={onClose}>Close</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default GroupMembersDialog;
