# -*- Mode: perl; indent-tabs-mode: nil -*-
#
# The contents of this file are subject to the Mozilla Public
# License Version 1.1 (the "License"); you may not use this file
# except in compliance with the License. You may obtain a copy of
# the License at http://www.mozilla.org/MPL/
#
# Software distributed under the License is distributed on an "AS
# IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
# implied. See the License for the specific language governing
# rights and limitations under the License.
#
# The Original Code is the Bugzilla Bug Tracking System.
#
# The Initial Developer of the Original Code is Netscape Communications
# Corporation. Portions created by Netscape are
# Copyright (C) 1998 Netscape Communications Corporation. All
# Rights Reserved.
#
# Contributor(s): Terry Weissman <terry@mozilla.org>
#                 Dawn Endico <endico@mozilla.org>
#                 Dan Mosedale <dmose@mozilla.org>
#                 Joe Robins <jmrobins@tgix.com>
#                 Jacob Steenhagen <jake@bugzilla.org>
#                 J. Paul Reed <preed@sigkill.com>
#                 Bradley Baetz <bbaetz@student.usyd.edu.au>
#

# This file defines all the parameters that we have a GUI to edit within
# Bugzilla.

# ATTENTION!!!!   THIS FILE ONLY CONTAINS THE DEFAULTS.
# You cannot change your live settings by editing this file.
# Only adding new parameters is done here.  Once the parameter exists, you
# must use %baseurl%/editparams.cgi from the web to edit the settings.

# This file is included via |do|, mainly because of circular dependancy issues
# (such as globals.pl -> Bugzilla::Config -> this -> Bugzilla::Config)
# which preclude compile time loading.

# Those issues may go away at some point, and the contents of this file
# moved somewhere else. Please try to avoid more dependancies from here
# to other code

# (Note that these aren't just added directly to Bugzilla::Config, because
# the backend prefs code is separate to this...)

use strict;
use vars qw(@param_list);

# Checking functions for the various values
# Some generic checking functions are included in Bugzilla::Config

sub check_priority {
    my ($value) = (@_);
    &::ConnectToDatabase();
    &::GetVersionTable();
    if (lsearch(\@::legal_priority, $value) < 0) {
        return "Must be a legal priority value: one of " .
            join(", ", @::legal_priority);
    }
    return "";
}

sub check_shadowdb {
    my ($value) = (@_);
    $value = trim($value);
    if ($value eq "") {
        return "";
    }
    &::ConnectToDatabase();
    &::SendSQL("SHOW DATABASES");
    while (&::MoreSQLData()) {
        my $n = &::FetchOneColumn();
        if (lc($n) eq lc($value)) {
            return "The $n database already exists.  If that's really the name you want to use for the backup, please CAREFULLY make the existing database go away somehow, and then try again.";
        }
    }
    &::SendSQL("CREATE DATABASE $value");
    &::SendSQL("INSERT INTO shadowlog (command) VALUES ('SYNCUP')", 1);
    return "";
}

sub check_urlbase {
    my ($url) = (@_);
    if ($url !~ m:^http.*/$:) {
        return "must be a legal URL, that starts with http and ends with a slash.";
    }
    return "";
}

sub check_webdotbase {
    my ($value) = (@_);
    $value = trim($value);
    if ($value eq "") {
        return "";
    }
    if($value !~ /^https?:/) {
        if(! -x $value) {
            return "The file path \"$value\" is not a valid executable.  Please specify the complete file path to 'dot' if you intend to generate graphs locally.";
        }
        # Check .htaccess allows access to generated images
        if(-e "data/webdot/.htaccess") {
            open HTACCESS, "data/webdot/.htaccess";
            if(! grep(/png/,<HTACCESS>)) {
                return "Dependency graph images are not accessible.\nDelete data/webdot/.htaccess and re-run checksetup.pl to rectify.\n";
            }
            close HTACCESS;
        }
    }
    return "";
}

# OK, here are the parameter definitions themselves.
#
# Each definition is a hash with keys:
#
# name    - name of the param
# desc    - description of the param (for editparams.cgi)
# type    - see below
# choices - (optional) see below
# default - default value for the param
# checker - (optional) checking function for validating parameter entry
#           It is called with the value of the param as the first arg and a
#           reference to the param's hash as the second argument
#
# The type value can be one of the following:
#
# t -- A short text entry field (suitable for a single line)
# l -- A long text field (suitable for many lines)
# b -- A boolean value (either 1 or 0)
# m -- A list of values, with many selectable (shows up as a select box)
#      To specify the list of values, make the 'choices' key be an array
#      reference of the valid choices. The 'default' key should be an array
#      reference for the list of selected values (which must appear in the
#      first anonymous array), i.e.:
#       {
#         name => 'multiselect',
#         desc => 'A list of options, choose many',
#         type => 'm',
#         choices => [ 'a', 'b', 'c', 'd' ],
#         default => [ 'a', 'd' ],
#         checker => \&check_multi
#       }
#
#      Here, 'a' and 'd' are the default options, and the user may pick any
#      combination of a, b, c, and d as valid options.
#
#      &check_multi should always be used as the param verification function
#      for list (single and multiple) parameter types.
#
# s -- A list of values, with one selectable (shows up as a select box)
#      To specify the list of values, make the 'choices' key be an array
#      reference of the valid choices. The 'default' key should be one of
#      those values, i.e.:
#       {
#         name => 'singleselect',
#         desc => 'A list of options, choose one',
#         type => 's',
#         choices => [ 'a', 'b', 'c' ],
#         default => 'b',
#         checker => \&check_multi
#       }
#
#      Here, 'b' is the default option, and 'a' and 'c' are other possible
#      options, but only one at a time! 
#
#      &check_multi should always be used as the param verification function
#      for list (single and multiple) parameter types.

# XXXX - would be nice for doeditparams to 'know' about types s and m, and call
# check_multi without it having to be explicitly specified here - bbaetz

@param_list = (
  {
   name => 'maintainer',
   desc => 'The email address of the person who maintains this installation ' .
           'of Bugzilla.',
   type => 't',
   default => 'THE MAINTAINER HAS NOT YET BEEN SET'
  },

  {
   name => 'urlbase',
   desc => 'The URL that is the common initial leading part of all Bugzilla ' .
           'URLs.',
   type => 't',
   default => 'http://cvs-mirror.mozilla.org/webtools/bugzilla/',
   checker => \&check_urlbase
  },

  {
   name => 'cookiepath',
   desc => 'Directory path under your document root that holds your ' .
           'Bugzilla installation. Make sure to begin with a /.',
   type => 't',
   default => '/'
  },

  {
   name => 'enablequips',
   desc => 'If this is on, Bugzilla displays a silly quip at the beginning ' .
           'of buglists, and lets users add to the list of quips. If this ' .
           'is frozen, Bugzilla will display the quip but not permit new ' .
           'additions.',
   type => 's',
   choices => ['on','frozen','off'],
   default => 'on',
   checker => \&check_multi
  },

  {
   name => 'usebuggroups',
   desc => 'If this is on, Bugzilla will associate a bug group with each ' .
           'product in the database, and use it for querying bugs.',
   type => 'b',
   default => 0
  },

  {
   name => 'usebuggroupsentry',
   desc => 'If this is on, Bugzilla will use product bug groups to restrict ' .
           'who can enter bugs.  Requires usebuggroups to be on as well.',
   type => 'b',
   default => 0
  },

  {
   name => 'shadowdb',
   desc => 'If non-empty, then this is the name of another database in ' .
           'which Bugzilla will keep a shadow read-only copy of everything. ' .
           'This is done so that long slow read-only operations can be used ' .
           'against this db, and not lock up things for everyone else. ' .
           'Turning on this parameter will create the given database ; be ' .
           'careful not to use the name of an existing database with useful ' .
           'data in it!',
   type => 't',
   default => '',
   checker => \&check_shadowdb
  },

  {
   name => 'queryagainstshadowdb',
   desc => 'If this is on, and the shadowdb is set, then queries will ' .
           'happen against the shadow database.',
   type => 'b',
   default => 0,
  },

  {
   name => 'useLDAP',
   desc => 'Turn this on to use an LDAP directory for user authentication ' .
           'instead of the Bugzilla database.  (User profiles will still be ' .
           'stored in the database, and will match against the LDAP user by ' .
           'email address.)',
   type => 'b',
   default => 0
  },

  {
   name => 'LDAPserver',
   desc => 'The name (and optionally port) of your LDAP server. (e.g. ' .
           'ldap.company.com, or ldap.company.com:portnum)',
   type => 't',
   default => ''
  },

  {
   name => 'LDAPBaseDN',
   desc => 'The BaseDN for authenticating users against. (e.g. ' .
           '"ou=People,o=Company")',
   type => 't',
   default => ''
  },

  {
   name => 'LDAPmailattribute',
   desc => 'The name of the attribute of a user in your directory that ' .
           'contains the email address.',
   type => 't',
   default => 'mail'
  },

  {
   name => 'mostfreqthreshold',
   desc => 'The minimum number of duplicates a bug needs to show up on the ' .
           '<a href="duplicates.cgi">most frequently reported bugs page</a>. ' .
           'If you have a large database and this page takes a long time to ' .
           'load, try increasing this number.',
   type => 't',
   default => '2'
  },

  {
   name => 'mybugstemplate',
   desc => 'This is the URL to use to bring up a simple \'all of my bugs\' ' .
           'list for a user.  %userid% will get replaced with the login ' .
           'name of a user.',
   type => 't',
   default => 'buglist.cgi?bug_status=NEW&amp;bug_status=ASSIGNED&amp;bug_status=REOPENED&amp;email1=%userid%&amp;emailtype1=exact&amp;emailassigned_to1=1&amp;emailreporter1=1'
  },

  {
   name => 'shutdownhtml',
   desc => 'If this field is non-empty, then Bugzilla will be completely ' .
           'disabled and this text will be displayed instead of all the ' .
           'Bugzilla pages.',
   type => 'l',
   default => ''
  },

  {
   name => 'sendmailnow',
   desc => 'If this is on, Bugzilla will tell sendmail to send any e-mail ' .
           'immediately. If you have a large number of users with a large ' .
           'amount of e-mail traffic, enabling this option may dramatically ' .
           'slow down Bugzilla. Best used for smaller installations of ' .
           'Bugzilla.',
   type => 'b',
   default => 0
  },

  {
   name => 'passwordmail',
   desc => 'The email that gets sent to people to tell them their password.' .
           'Within this text, %mailaddress% gets replaced by the person\'s ' .
           'email address, %login% gets replaced by the person\'s login ' .
           '(usually the same thing), and %password% gets replaced by their ' .
           'password.  %<i>anythingelse</i>% gets replaced by the ' .
           'definition of that parameter (as defined on this page).',
   type => 'l',
   default => 'From: bugzilla-daemon
To: %mailaddress%
Subject: Your Bugzilla password.

To use the wonders of Bugzilla, you can use the following:

 E-mail address: %login%
       Password: %password%

 To change your password, go to:
 %urlbase%userprefs.cgi
'
  },

  {
   name => 'newchangedmail',
   desc => 'The email that gets sent to people when a bug changes. Within ' .
           'this text, %to% gets replaced with the e-mail address of the ' .
           'person recieving the mail.  %bugid% gets replaced by the bug ' .
           'number.  %diffs% gets replaced with what\'s changed. ' .
           '%neworchanged% is "New:" if this mail is reporting a new bug or ' .
           'empty if changes were made to an existing one. %summary% gets ' .
           'replaced by the summary of this bug. %reasonsheader% is ' .
           'replaced by an abbreviated list of reasons why the user is ' .
           'getting the email, suitable for use in an email header (such ' .
           'as X-Bugzilla-Reason). %reasonsbody% is replaced by text that ' .
           'explains why the user is getting the email in more user ' .
           'friendly text than %reasonsheader%. %<i>anythingelse</i>% gets ' .
           'replaced by the definition of that parameter (as defined on ' .
           'this page).',
   type => 'l',
   default => 'From: bugzilla-daemon
To: %to%
Subject: [Bug %bugid%] %neworchanged%%summary%
X-Bugzilla-Reason: %reasonsheader%

%urlbase%show_bug.cgi?id=%bugid%

%diffs%



%reasonsbody%'
  },

  {
   name => 'whinedays',
   desc => 'The number of days that we\'ll let a bug sit untouched in a NEW ' .
           'state before our cronjob will whine at the owner.',
   type => 't',
   default => 7
  },

  {
   name => 'whinemail',
   desc => 'The email that gets sent to anyone who has a NEW bug that '.
           'hasn\'t been touched for more than <b>whinedays</b>.  Within ' .
           'this text, %email% gets replaced by the offender\'s email ' .
           'address. %userid% gets replaced by the offender\'s bugzilla ' .
           'login (which, in most installations, is the same as the email ' .
           ' address.) %<i>anythingelse</i>% gets replaced by the definition ' .
           'of that parameter (as defined on this page).<p> It is a good ' .
           'idea to make sure this message has a valid From: address, so ' .
           'that if the mail bounces, a real person can know that there are ' .
           'bugs assigned to an invalid address.',
   type => 'l',
   default => 'From: %maintainer%
To: %email%
Subject: Your Bugzilla buglist needs attention.

[This e-mail has been automatically generated.]

You have one or more bugs assigned to you in the Bugzilla 
bugsystem (%urlbase%) that require
attention.

All of these bugs are in the NEW state, and have not been touched
in %whinedays% days or more.  You need to take a look at them, and 
decide on an initial action.

Generally, this means one of three things:

(1) You decide this bug is really quick to deal with (like, it\'s INVALID),
    and so you get rid of it immediately.
(2) You decide the bug doesn\'t belong to you, and you reassign it to someone
    else.  (Hint: if you don\'t know who to reassign it to, make sure that
    the Component field seems reasonable, and then use the "Reassign bug to
    owner of selected component" option.)
(3) You decide the bug belongs to you, but you can\'t solve it this moment.
    Just use the "Accept bug" command.

To get a list of all NEW bugs, you can use this URL (bookmark it if you like!):

   %urlbase%buglist.cgi?bug_status=NEW&assigned_to=%userid%

Or, you can use the general query page, at
%urlbase%query.cgi.

Appended below are the individual URLs to get to all of your NEW bugs that
haven\'t been touched for a week or more.

You will get this message once a day until you\'ve dealt with these bugs!

'
  },

  {
   name => 'defaultquery',
   desc => 'This is the default query that initially comes up when you ' .
           'submit a bug.  It\'s in URL parameter format, which makes it ' .
           'hard to read.  Sorry!',
   type => 't',
   default => 'bug_status=NEW&bug_status=ASSIGNED&bug_status=REOPENED&emailassigned_to1=1&emailassigned_to2=1&emailreporter2=1&emailcc2=1&emailqa_contact2=1&order=%22Importance%22'
  },

  {
   name => 'letsubmitterchoosepriority',
   desc => 'If this is on, then people submitting bugs can choose an ' .
           'initial priority for that bug.  If off, then all bugs initially ' .
           'have the default priority selected below.',
   type => 'b',
   default => 1
  },

  {
   name => 'defaultpriority',
   desc => 'This is the priority that newly entered bugs are set to.',
   type => 't',
   default => 'P2',
   checker => \&check_priority
  },

  {
   name => 'usetargetmilestone',
   desc => 'Do you wish to use the Target Milestone field?',
   type => 'b',
   default => 0
  },

  {
   name => 'nummilestones',
   desc => 'If using Target Milestone, how many milestones do you wish to
   appear?',
   type => 't',
   default => 10,
   checker => \&check_numeric
  },

  {
   name => 'curmilestone',
   desc => 'If using Target Milestone, Which milestone are we working ' .
           'toward right now?',
   type => 't',
   default => 1,
   checker => \&check_numeric
  },

  {
   name => 'musthavemilestoneonaccept',
   desc => 'If you are using Target Milestone, do you want to require that ' .
           'the milestone be set in order for a user to ACCEPT a bug?',
   type => 'b',
   default => 0
  },

  {
   name => 'useqacontact',
   desc => 'Do you wish to use the QA Contact field?',
   type => 'b',
   default => 0
  },

  {
   name => 'usestatuswhiteboard',
   desc => 'Do you wish to use the Status Whiteboard field?',
   type => 'b',
   default => 0
  },

  {
   name => 'usebrowserinfo',
   desc => 'Do you want bug reports to be assigned an OS & Platform based ' .
           'on the browser the user makes the report from?',
   type => 'b',
   default => 1
  },

  {
   name => 'usevotes',
   desc => 'Do you wish to allow users to vote for bugs? Note that in order ' .
           'for this to be effective, you will have to change the maximum ' .
           'votes allowed in a product to be non-zero in ' .
           '<a href="editproducts.cgi">the product edit page</a>.',
   type => 'b',
   default => 1
  },

  {
   name => 'usebugaliases',
   desc => 'Do you wish to use bug aliases, which allow you to assign bugs ' .
           'an easy-to-remember name by which you can refer to them?',
   type => 'b',
   default => 0
  },

  {
   name => 'webdotbase',
   desc => 'It is possible to show graphs of dependent bugs. You may set ' .
           'this parameter to any of the following:
   <ul>
   <li>A complete file path to \'dot\' (part of <a
       href="http://www.graphviz.org">GraphViz</a>) will generate the graphs
   locally.</li>
   <li>A URL prefix pointing to an installation of the <a
   href="http://www.research.att.com/~north/cgi-bin/webdot.cgi">webdot
   package</a> will generate the graphs remotely.</li>
   <li>A blank value will disable dependency graphing.</li>
   </ul>
   The default value is a publically-accessible webdot server. If you change
   this value, make certain that the webdot server can read files from your
   data/webdot directory. On Apache you do this by editing the .htaccess file,
   for other systems the needed measures may vary. You can run checksetup.pl
   to recreate the .htaccess file if it has been lost.',
   type => 't',
   default => 'http://www.research.att.com/~north/cgi-bin/webdot.cgi/%urlbase%',
   checker => \&check_webdotbase
  },

  {
   name => 'emailregexp',
   desc => 'This defines the regexp to use for legal email addresses. The ' .
           'default tries to match fully qualified email addresses. Another ' .
           'popular value to put here is <tt>^[^@]+$</tt>, which means ' .
           '"local usernames, no @ allowed."',
   type => 't',
   default => q:^[^@]+@[^@]+\\.[^@]+$:,
   checker => \&check_regexp
  },

  {
   name => 'emailregexpdesc',
   desc => 'This describes in english words what kinds of legal addresses ' .
           'are allowed by the <tt>emailregexp</tt> param.',
   type => 'l',
   default => 'A legal address must contain exactly one \'@\', and at least ' .
              'one \'.\' after the @.'
  },

  {
   name => 'emailsuffix',
   desc => 'This is a string to append to any email addresses when actually ' .
           'sending mail to that address.  It is useful if you have changed ' .
           'the <tt>emailregexp</tt> param to only allow local usernames, ' .
           'but you want the mail to be delivered to username@my.local.hostname.',
   type => 't',
   default => ''
  },

  {
   name => 'voteremovedmail',
   desc => 'This is a mail message to send to anyone who gets a vote removed ' .
           'from a bug for any reason.  %to% gets replaced by the person who ' .
           'used to be voting for this bug.  %bugid% gets replaced by the ' .
           'bug number. %reason% gets replaced by a short reason describing ' .
           'why the vote(s) were removed. %votesremoved%, %votesold% and ' .
           '%votesnew% is the number of votes removed, before and after ' .
           'respectively. %votesremovedtext%, %votesoldtext% and ' .
           '%votesnewtext% are these as sentences, eg "You had 2 votes on ' .
           'this bug."  %count% is also supported for backwards ' .
           'compatibility. %<i>anythingelse</i>% gets replaced by the ' .
           'definition of that parameter (as defined on this page).',
   type => 'l',
   default => 'From: bugzilla-daemon
To: %to%
Subject: [Bug %bugid%] Some or all of your votes have been removed.

Some or all of your votes have been removed from bug %bugid%.

%votesoldtext%

%votesnewtext%

Reason: %reason%

%urlbase%show_bug.cgi?id=%bugid%
'
  },

  {
   name => 'allowbugdeletion',
   desc => 'The pages to edit products and components and versions can delete ' .
           'all associated bugs when you delete a product (or component or ' .
           'version).  Since that is a pretty scary idea, you have to turn on ' .
           'this option before any such deletions will ever happen.',
   type => 'b',
   default => 0
  },

  {
   name => 'allowemailchange',
   desc => 'Users can change their own email address through the preferences. ' .
           'Note that the change is validated by emailing both addresses, so ' .
           'switching this option on will not let users use an invalid address.',
   type => 'b',
   default => 0
  },

  {
   name => 'allowuserdeletion',
   desc => 'The pages to edit users can also let you delete a user. But there ' .
           'is no code that goes and cleans up any references to that user in ' .
           'other tables, so such deletions are kinda scary. So, you have to ' .
           'turn on this option before any such deletions will ever happen.',
   type => 'b',
   default => 0
  },

  {
   name => 'browserbugmessage',
   desc => 'If bugzilla gets unexpected data from the browser, in addition to ' .
           'displaying the cause of the problem, it will output this HTML as ' .
           'well.',
   type => 'l',
   default => 'this may indicate a bug in your browser.'
  },

  {
   name => 'commentonaccept',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'he accepts the bug',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonclearresolution',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'the bug\'s resolution is cleared',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonconfirm',
   desc => 'If this option is on, the user needs to enter a short comment ' .
           'when confirming a bug',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonresolve',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'the bug is resolved',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonreassign',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'the bug is reassigned',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonreassignbycomponent',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'the bug is reassigned by component',
   type => 'b',
   default => 0
  },
  {
   name => 'commentonreopen',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'the bug is reopened',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonverify',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'the bug is verified',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonclose',
   desc => 'If this option is on, the user needs to enter a short comment if ' .
           'the bug is closed',
   type => 'b',
   default => 0
  },

  {
   name => 'commentonduplicate',
   desc => 'If this option is on, the user needs to enter a short comment ' .
           'if the bug is marked as duplicate',
   type => 'b',
   default => 0
  },

  {
   name => 'supportwatchers',
   desc => 'Support one user watching (ie getting copies of all related ' .
           'email about) another\'s bugs.  Useful for people going on ' .
           'vacation, and QA folks watching particular developers\' bugs',
   type => 'b',
   default => 0
  },

  {
   name => 'move-enabled',
   desc => 'If this is on, Bugzilla will allow certain people to move bugs ' .
           'to the defined database.',
   type => 'b',
   default => 0
  },

  {
   name => 'move-button-text',
   desc => 'The text written on the Move button. Explain where the bug is ' .
           'being moved to.',
   type => 't',
   default => 'Move To Bugscape'
  },

  {
   name => 'move-to-url',
   desc => 'The URL of the database we allow some of our bugs to be moved to.',
   type => 't',
   default => ''
  },

  {
   name => 'move-to-address',
   desc => 'To move bugs, an email is sent to the target database. This is ' .
           'the email address that database uses to listen for incoming bugs.',
   type => 't',
   default => 'bugzilla-import'
  },

  {
   name => 'moved-from-address',
   desc => 'To move bugs, an email is sent to the target database. This is ' .
           'the email address from which this mail, and error messages are ' .
           'sent.',
   type => 't',
   default => 'bugzilla-admin'
  },

  {
   name => 'movers',
   desc => 'A list of people with permission to move bugs and reopen moved ' .
           'bugs (in case the move operation fails).',
   type => 't',
   default => ''
  },

  {
   name => 'moved-default-product',
   desc => 'Bugs moved from other databases to here are assigned to this ' .
           'product.',
   type => 't',
   default => ''
  },

  {
   name => 'moved-default-component',
   desc => 'Bugs moved from other databases to here are assigned to this ' .
           'component.',
   type => 't',
   default => ''
  },

  # The maximum size (in bytes) for patches and non-patch attachments.
  # The default limit is 1000KB, which is 24KB less than mysql's default
  # maximum packet size (which determines how much data can be sent in a
  # single mysql packet and thus how much data can be inserted into the
  # database) to provide breathing space for the data in other fields of
  # the attachment record as well as any mysql packet overhead (I don't
  # know of any, but I suspect there may be some.)

  {
   name => 'maxpatchsize',
   desc => 'The maximum size (in kilobytes) of patches.  Bugzilla will not ' .
           'accept patches greater than this number of kilobytes in size.' .
           'To accept patches of any size (subject to the limitations of ' .
           'your server software), set this value to zero.',
   type => 't',
   default => '1000',
   checker => \&check_numeric
  },

  {
   name => 'maxattachmentsize',
   desc => 'The maximum size (in kilobytes) of non-patch attachments. ' .
           'Bugzilla will not accept attachments greater than this number' .
           'of kilobytes in size.  To accept attachments of any size ' .
           '(subject to the limitations of your server software), set this ' .
           'value to zero.',
   type => 't',
   default => '1000',
   checker => \&check_numeric
  },

  {
   name => 'insidergroup',
   desc => 'The name of the group of users who can see/change private ' .
           'comments and attachments.',
   type => 't',
   default => ''
  },
);

1;

