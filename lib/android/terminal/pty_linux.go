//go:build linux

package main

import (
	"errors"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

type ptyProcess struct {
	master  *os.File
	command *exec.Cmd
	once    sync.Once
}

func ioctl(fd uintptr, op uintptr, value unsafe.Pointer) error {
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, op, uintptr(value))
	if errno != 0 {
		return errno
	}
	return nil
}
func fileIoctl(file *os.File, op uintptr, value unsafe.Pointer) error {
	// File.Fd switches a pollable descriptor to blocking mode; keep deadlines intact.
	raw, err := file.SyscallConn()
	if err != nil {
		return err
	}
	var result error
	if err := raw.Control(func(fd uintptr) { result = ioctl(fd, op, value) }); err != nil {
		return err
	}
	return result
}
func startPTY(columns, rows int, output func([]byte), exited func(int)) (terminal, error) {
	fd, err := syscall.Open("/dev/ptmx", syscall.O_RDWR|syscall.O_NOCTTY|syscall.O_CLOEXEC|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	master := os.NewFile(uintptr(fd), "station-pty")
	ok := false
	defer func() {
		if !ok {
			master.Close()
		}
	}()
	var unlock, number uint32
	if err = fileIoctl(master, 0x40045431, unsafe.Pointer(&unlock)); err != nil {
		return nil, err
	} // TIOCSPTLCK
	if err = fileIoctl(master, 0x80045430, unsafe.Pointer(&number)); err != nil {
		return nil, err
	} // TIOCGPTN
	slave, err := os.OpenFile("/dev/pts/"+strconv.FormatUint(uint64(number), 10), os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return nil, err
	}
	defer slave.Close()
	shell := "/system/bin/sh"
	if _, err := os.Stat(shell); errors.Is(err, os.ErrNotExist) {
		shell = "/bin/sh"
	} // Linux regression fixture.
	cmd := exec.Command(shell, "-i")
	cmd.Dir = "/"
	cmd.Env = append(os.Environ(), "TERM=xterm-256color", "HOME=/", "PS1=phone $ ")
	cmd.Stdin = slave
	cmd.Stdout = slave
	cmd.Stderr = slave
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true, Setctty: true, Ctty: 0}
	p := &ptyProcess{master: master, command: cmd}
	if err := p.Resize(columns, rows); err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	ok = true
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		buffer := make([]byte, 8192)
		for {
			n, err := master.Read(buffer)
			if n > 0 {
				output(buffer[:n])
			}
			if err != nil {
				return
			}
		}
	}()
	go func() {
		cmd.Wait()
		// Reap foreground/background groups too; shell job control uses new groups.
		killSession(cmd.Process.Pid)
		select {
		case <-readDone:
		case <-time.After(250 * time.Millisecond):
		}
		p.Close()
		<-readDone
		exited(cmd.ProcessState.ExitCode())
	}()
	return p, nil
}
func (p *ptyProcess) Write(data []byte) error {
	if err := p.master.SetWriteDeadline(time.Now().Add(200 * time.Millisecond)); err != nil {
		return err
	}
	_, err := p.master.Write(data)
	return err
}
func (p *ptyProcess) Resize(columns, rows int) error {
	window := [4]uint16{uint16(rows), uint16(columns), 0, 0}
	return fileIoctl(p.master, 0x5414, unsafe.Pointer(&window[0])) // TIOCSWINSZ -> SIGWINCH
}
func (p *ptyProcess) Close() {
	p.once.Do(func() {
		// Killing only the shell group leaves a job-control foreground command alive.
		var foreground int32
		if fileIoctl(p.master, 0x540f, unsafe.Pointer(&foreground)) == nil && foreground > 1 {
			syscall.Kill(-int(foreground), syscall.SIGKILL)
		}
		pid := p.command.Process
		if pid != nil {
			killSession(pid.Pid)
			syscall.Kill(-pid.Pid, syscall.SIGKILL)
		}
		p.master.Close()
	})
}
func killSession(id int) {
	entries, _ := os.ReadDir("/proc")
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid <= 1 {
			continue
		}
		stat, err := os.ReadFile("/proc/" + entry.Name() + "/stat")
		if err != nil {
			continue
		}
		end := strings.LastIndexByte(string(stat), ')')
		if end < 0 {
			continue
		}
		fields := strings.Fields(string(stat[end+1:]))
		if len(fields) >= 4 && fields[3] == strconv.Itoa(id) {
			syscall.Kill(pid, syscall.SIGKILL)
		}
	}
}
