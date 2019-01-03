# -*- coding:utf-8 -*-

class test(object):
    def __init__(self, c):
        self.a = 2
        self.b = c
        
    def hello(self):
        print(self.a)
        print(self.b)
        print(c)
        
        
def testdic():
    letter = {}
    i = 0
    for s in "aabbccdd":
        if letter.__contains__(s) == False:
            letter[s] = i
            i = i+1
    print(letter)
    for key in letter:
        print(key, letter[key])
        
def strjoin():
    s1 = "abc "
    s2 = "123 "
    s3 = "哈哈哈"
    s = s1 + s2 + s3
    print(s)
    
def writefile():
    f = open("test.txt", "w")
    f.write("hello")
    f.close()
        
if __name__ == "__main__":
     #hello = test("nihao")
     #hello.hello()
     #testdic()
     #strjoin()
     writefile()
