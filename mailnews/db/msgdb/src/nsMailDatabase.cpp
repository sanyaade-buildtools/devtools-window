/* -*- Mode: C++; tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 *
 * The contents of this file are subject to the Netscape Public License
 * Version 1.0 (the "NPL"); you may not use this file except in
 * compliance with the NPL.  You may obtain a copy of the NPL at
 * http://www.mozilla.org/NPL/
 *
 * Software distributed under the NPL is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the NPL
 * for the specific language governing rights and limitations under the
 * NPL.
 *
 * The Initial Developer of this code under the NPL is Netscape
 * Communications Corporation.  Portions created by Netscape are
 * Copyright (C) 1999 Netscape Communications Corporation.  All Rights
 * Reserved.
 */

#include "msgCore.h"
#include "nsMailDatabase.h"
#include "nsDBFolderInfo.h"
#include "nsMsgLocalFolderHdrs.h"
#include "nsFileStream.h"

nsMailDatabase::nsMailDatabase()
: m_folderName("")
{
	m_folderStream = NULL;
}

nsMailDatabase::~nsMailDatabase()
{
}


/* static */ nsresult	nsMailDatabase::Open(nsFilePath &dbName, PRBool create, nsMailDatabase** pMessageDB,
					 PRBool upgrading /*=PR_FALSE*/)
{
	nsMailDatabase	*mailDB;
	int				statResult;
	XP_StatStruct	st;
	PRBool			newFile = PR_FALSE;
	nsDBFolderInfo	*folderInfo = NULL;

// OK, dbName is probably folder name, since I can't figure out how nsFilePath interacts
// with xpFileTypes and its related routines.
	const char *folderName = dbName;

	*pMessageDB = NULL;

	mailDB = (nsMailDatabase *) FindInCache(dbName);
	if (mailDB)
	{
		*pMessageDB = mailDB;
		mailDB->AddRef();
		return(NS_OK);
	}
	// if the old summary doesn't exist, we're creating a new one.
	if (XP_Stat (folderName, &st, xpMailFolderSummary) && create)
		newFile = PR_TRUE;


	mailDB = new nsMailDatabase;

	if (!mailDB)
		return NS_ERROR_OUT_OF_MEMORY;
	
	mailDB->m_folderName = PL_strdup(folderName);

	dbName = WH_FileName(folderName, xpMailFolderSummary);
	if (!dbName) 
		return NS_ERROR_OUT_OF_MEMORY;
	// stat file before we open the db, because if we've latered
	// any messages, handling latered will change time stamp on
	// folder file.
	statResult = XP_Stat (folderName, &st, xpMailFolder);

	nsresult err = mailDB->OpenMDB(dbName, create);
	PR_Free(dbName);

	if (NS_SUCCEEDED(err))
	{
		folderInfo = mailDB->GetDBFolderInfo();
		if (folderInfo == NULL)
		{
			err = NS_MSG_ERROR_FOLDER_SUMMARY_OUT_OF_DATE;
		}
		else
		{
			// if opening existing file, make sure summary file is up to date.
			// if caller is upgrading, don't return NS_MSG_ERROR_FOLDER_SUMMARY_OUT_OF_DATE so the caller
			// can pull out the transfer info for the new db.
			if (!newFile && !statResult && !upgrading)
			{
				if (folderInfo->m_folderSize != st.st_size ||
						folderInfo->m_folderDate != st.st_mtime || folderInfo->GetNumNewMessages() < 0)
					err = NS_MSG_ERROR_FOLDER_SUMMARY_OUT_OF_DATE;
			}
			// compare current version of db versus filed out version info.
			if (mailDB->GetCurVersion() != folderInfo->GetDiskVersion())
				err = NS_MSG_ERROR_FOLDER_SUMMARY_OUT_OF_DATE;
		}
		if (err != NS_OK)
		{
			mailDB->Close();
			mailDB = NULL;
		}
	}
	if (err != NS_OK || newFile)
	{
		// if we couldn't open file, or we have a blank one, and we're supposed 
		// to upgrade, updgrade it.
		if (newFile && !upgrading)	// caller is upgrading, and we have empty summary file,
		{					// leave db around and open so caller can upgrade it.
			err = NS_MSG_ERROR_FOLDER_SUMMARY_MISSING;
		}
		else if (err != NS_OK)
		{
			*pMessageDB = NULL;
			delete mailDB;
		}
	}
	if (err == NS_OK || err == NS_MSG_ERROR_FOLDER_SUMMARY_MISSING)
	{
		*pMessageDB = mailDB;
		GetDBCache()->AppendElement(mailDB);
//		if (err == NS_OK)
//			mailDB->HandleLatered();

	}
	return err;
}

/* static */ nsresult nsMailDatabase::CloneInvalidDBInfoIntoNewDB(nsFilePath &pathName, nsMailDatabase** pMailDB)
{
	nsresult ret = NS_OK;
	return ret;
}

nsresult nsMailDatabase::OnNewPath (nsFilePath &newPath)
{
	nsresult ret = NS_OK;
	return ret;
}

nsresult nsMailDatabase::DeleteMessages(nsMsgKeyArray &messageKeys, nsIDBChangeListener *instigator)
{
	nsresult ret = NS_OK;
	m_folderStream = new nsIOFileStream(m_dbName);
	ret = nsMsgDatabase::DeleteMessages(messageKeys, instigator);
	if (m_folderStream)
		delete m_folderStream;
	m_folderStream = NULL;
	SetFolderInfoValid(m_folderName, 0, 0);
	return ret;
}


// Helper routine - lowest level of flag setting
PRBool nsMailDatabase::SetHdrFlag(nsMsgHdr *msgHdr, PRBool bSet, MsgFlags flag)
{
	nsIOFileStream *fileStream = NULL;
	PRBool		ret = PR_FALSE;

	if (nsMsgDatabase::SetHdrFlag(msgHdr, bSet, flag))
	{
		UpdateFolderFlag(msgHdr, bSet, flag, &fileStream);
		if (fileStream != NULL)
		{
			delete fileStream;
			SetFolderInfoValid(m_folderName, 0, 0);
		}
		ret = PR_TRUE;
	}
	return ret;
}

#ifdef XP_MAC
extern PRFileDesc *gIncorporateFID;
extern const char* gIncorporatePath;
#endif // XP_MAC

// ### should move this into some utils class...
int msg_UnHex(char C)
{
	return ((C >= '0' && C <= '9') ? C - '0' :
			((C >= 'A' && C <= 'F') ? C - 'A' + 10 :
			 ((C >= 'a' && C <= 'f') ? C - 'a' + 10 : 0)));
}


// We let the caller close the file in case he's updating a lot of flags
// and we don't want to open and close the file every time through.
// As an experiment, try caching the fid in the db as m_folderFile.
// If this is set, use it but don't return *pFid.
void nsMailDatabase::UpdateFolderFlag(nsMsgHdr *mailHdr, PRBool bSet, 
							  MsgFlags flag, nsIOFileStream **ppFileStream)
{
	static char buf[30];
	nsIOFileStream *fileStream = (m_folderStream) ? m_folderStream : *ppFileStream;
//#ifdef GET_FILE_STUFF_TOGETHER
#ifdef XP_MAC
	// This is a horrible hack and we should make sure we don't need it anymore.
	// It has to do with multiple people having the same file open, I believe, but the
	// mac file system only has one handle, and they compete for the file position.
	// Prevent closing the file from under the incorporate stuff. #82785.
	int32 savedPosition = -1;
	if (!fid && gIncorporatePath && !XP_STRCMP(m_folderName, gIncorporatePath))
	{
		fid = gIncorporateFID;
		savedPosition = ftell(gIncorporateFID); // so we can restore it.
	}
#endif // XP_MAC
	if (mailHdr->GetStatusOffset() > 0) 
	{
		
		if (fileStream == NULL) 
		{
			fileStream = new nsIOFileStream(m_folderName);
		}
		if (fileStream) 
		{
			PRUint32 position = mailHdr->GetStatusOffset() + mailHdr->GetMessageOffset();
			PR_ASSERT(mailHdr->GetStatusOffset() < 10000);
			fileStream->seek(position);
			buf[0] = '\0';
			if (fileStream->readline(buf, sizeof(buf))) 
			{
				if (strncmp(buf, X_MOZILLA_STATUS, X_MOZILLA_STATUS_LEN) == 0 &&
					strncmp(buf + X_MOZILLA_STATUS_LEN, ": ", 2) == 0 &&
					strlen(buf) > X_MOZILLA_STATUS_LEN + 6) 
				{
		            uint16 flags = mailHdr->GetMozillaStatusFlags();
					if (!(flags & MSG_FLAG_EXPUNGED))
					{
						int i;
						char *p = buf + X_MOZILLA_STATUS_LEN + 2;
					
						for (i=0, flags = 0; i<4; i++, p++)
						{
							flags = (flags << 4) | msg_UnHex(*p);
						}
						flags = (flags & MSG_FLAG_QUEUED) |
							(mailHdr->GetMozillaStatusFlags() & 
							 ~MSG_FLAG_RUNTIME_ONLY);
					}
					else
					{
						flags &= ~MSG_FLAG_RUNTIME_ONLY;
					}
					fileStream->seek(position);
					// We are filing out old Cheddar flags here
					PR_snprintf(buf, sizeof(buf), X_MOZILLA_STATUS_FORMAT, flags);
					fileStream->write(buf, PL_strlen(buf));

					// time to upate x-mozilla-status2
					position = fileStream->tell();
					fileStream->seek(position + LINEBREAK_LEN);
					if (fileStream->readline(buf, sizeof(buf))) 
					{
						if (strncmp(buf, X_MOZILLA_STATUS2, X_MOZILLA_STATUS2_LEN) == 0 &&
							strncmp(buf + X_MOZILLA_STATUS2_LEN, ": ", 2) == 0 &&
							strlen(buf) > X_MOZILLA_STATUS2_LEN + 10) 
						{
							uint32 dbFlags = mailHdr->GetFlags();
							dbFlags &= (MSG_FLAG_MDN_REPORT_NEEDED | MSG_FLAG_MDN_REPORT_SENT | MSG_FLAG_TEMPLATE);
							fileStream->seek(position + LINEBREAK_LEN);
							PR_snprintf(buf, sizeof(buf), X_MOZILLA_STATUS2_FORMAT, dbFlags);
							fileStream->write(buf, PL_strlen(buf));
						}
					}
				} else 
				{
					printf("Didn't find %s where expected at position %ld\n"
						  "instead, found %s.\n",
						  X_MOZILLA_STATUS, (long) position, buf);
					SetReparse(TRUE);
				}			
			} 
			else 
			{
				printf("Couldn't read old status line at all at position %ld\n",
						(long) position);
				SetReparse(TRUE);
			}
#ifdef XP_MAC
			// Restore the file position
			if (savedPosition >= 0)
				XP_FileSeek(fid, savedPosition, SEEK_SET);
#endif
		}
		else
		{
			printf("Couldn't open mail folder for update%s!\n", m_folderName);
			PR_ASSERT(PR_FALSE);
		}
	}
//#endif // GET_FILE_STUFF_TOGETHER
#ifdef XP_MAC
	if (!m_folderStream && fid != gIncorporateFID)
#else
	if (!m_folderStream)
#endif
		*ppFileStream = fileStream; // This tells the caller that we opened the file, and please to close it.
}

/* static */  nsresult nsMailDatabase::SetSummaryValid(PRBool valid)
{
	nsresult ret = NS_OK;
	struct stat st;

	if (stat(m_dbName, &st)) 
		return NS_MSG_ERROR_FOLDER_MISSING;

	if (valid)
	{
		m_dbFolderInfo->SetFolderSize(st.st_size);
		m_dbFolderInfo->SetFolderDate(st.st_mtime);
	}
	else
	{
		m_dbFolderInfo->SetFolderDate(0);	// that ought to do the trick.
	}
	return ret;
}

nsresult nsMailDatabase::GetFolderName(nsString &folderName)
{
	folderName = m_folderName;
	return NS_OK;
}


// The master is needed to find the folder info corresponding to the db.
// Perhaps if we passed in the folder info when we opened the db, 
// we wouldn't need the master. I don't remember why we sometimes need to
// get from the db to the folder info, but it's probably something like
// some poor soul who has a db pointer but no folderInfo.


MSG_FolderInfo *nsMailDatabase::GetFolderInfo()
{
	PR_ASSERT(PR_FALSE);
	return NULL;
}
	
	// for offline imap queued operations
	// these are in the base mail class (presumably) because offline moves between online and offline
	// folders can cause these operations to be stored in local mail folders.
nsresult nsMailDatabase::ListAllOfflineOpIds(nsMsgKeyArray &outputIds)
{
	nsresult ret = NS_OK;
	return ret;
}

int nsMailDatabase::ListAllOfflineDeletes(nsMsgKeyArray &outputIds)
{
	nsresult ret = NS_OK;
	return ret;
}
nsresult nsMailDatabase::GetOfflineOpForKey(MessageKey opKey, PRBool create, nsOfflineImapOperation **)
{
	nsresult ret = NS_OK;
	return ret;
}

nsresult nsMailDatabase::AddOfflineOp(nsOfflineImapOperation *op)
{
	nsresult ret = NS_OK;
	return ret;
}

nsresult DeleteOfflineOp(MessageKey opKey)
{
	nsresult ret = NS_OK;
	return ret;
}

nsresult SetSourceMailbox(nsOfflineImapOperation *op, const char *mailbox, MessageKey key)
{
	nsresult ret = NS_OK;
	return ret;
}

	
nsresult nsMailDatabase::GetIdsWithNoBodies (nsMsgKeyArray &bodylessIds)
{
	nsresult ret = NS_OK;
	return ret;
}
