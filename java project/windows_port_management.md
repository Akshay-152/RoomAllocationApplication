# Windows Port Management Guide

Commands for checking open/listening ports and safely stopping applications that use them. Run in **PowerShell** or **Command Prompt** as indicated.

> **Important:** A port is not a process. To free a listening port, stop the application that owns it. Do **not** terminate all listening processes: Windows and network services rely on many of them.

## 1. Check open and listening ports

List all TCP connections and UDP endpoints, with process IDs (PIDs):

```powershell
netstat -ano
```

Show listening TCP ports only:

```powershell
netstat -ano | findstr LISTENING
```

Check port 8080 (the `:8080` filter may also match remote endpoints; check the **Local Address** column):

```powershell
netstat -ano | findstr :8080
```

PowerShell alternative:

```powershell
Get-NetTCPConnection -State Listen |
    Select-Object LocalAddress, LocalPort, OwningProcess
```

Show UDP endpoints:

```powershell
netstat -ano -p UDP
```

## 2. Find the application using a port

The last column of `netstat -ano` is the PID. Replace `17752` with your actual PID:

```powershell
tasklist /FI "PID eq 17752"
```

PowerShell command showing TCP listening ports with process names:

```powershell
Get-NetTCPConnection -State Listen |
    ForEach-Object {
        $p = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
        [PSCustomObject]@{
            Port = $_.LocalPort
            PID = $_.OwningProcess
            Process = $p.ProcessName
        }
    } | Sort-Object Port | Format-Table -AutoSize
```

## 3. Free a port by stopping its application

First verify that the PID belongs to your own application, such as your Java development server. Then stop it normally (for example, press **Ctrl+C** in the terminal where the server is running).

If the process does not stop, use this command, replacing the PID:

```powershell
taskkill /PID 17752 /F
```

Administrator permissions may be required. `/F` forces termination and may cause unsaved data to be lost.

For a Java application, identify the correct Java PID first. Avoid killing every `java.exe` process because unrelated projects may be running.

## 4. Check whether port 8080 is free

```powershell
netstat -ano | findstr :8080
```

If no local listening entry for `:8080` appears, no TCP process is listening on that local port at that moment.

## 5. Firewall: block unsolicited inbound connections

**This does not close ports or stop processes.** It changes the default inbound policy, and existing allow rules may still permit traffic.

Run PowerShell as Administrator:

```powershell
Set-NetFirewallProfile -Profile Domain,Private,Public -DefaultInboundAction Block
```

Be careful on a remotely managed machine: firewall changes can disrupt access. Review existing firewall rules before changing them.

## 6. Recommended safe workflow

1. Run `netstat -ano | findstr LISTENING`.
2. Identify the port you want to free, such as `8080`.
3. Find its PID and run `tasklist /FI "PID eq <PID>"` (replace `<PID>` with the number).
4. Stop the application normally if possible.
5. Use `taskkill /PID <PID> /F` only when necessary.
6. Recheck the port using `netstat -ano | findstr :8080`.

**Avoid “close all ports” scripts.** They can terminate essential Windows services, disconnect networking, and interrupt other applications. Selectively stop your own development servers instead.
